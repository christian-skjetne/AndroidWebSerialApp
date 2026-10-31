package dev.webserial.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class FileBridge {
    private static final int CHUNK = 16384;
    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(20000);
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, InputStream> readers = new HashMap<>();
    private final Map<String, Write> writers = new HashMap<>();
    private volatile int generation;
    private volatile boolean destroyed;
    private Picker picker;
    private AlertDialog permissionDialog;

    private static final class Grant {
        final Uri uri;
        volatile boolean writable;
        Grant(Uri uri, boolean writable) { this.uri = uri; this.writable = writable; }
    }

    private static final class Write {
        final Grant grant;
        final File file;
        final RandomAccessFile stream;
        Write(Grant grant, File file, RandomAccessFile stream) {
            this.grant = grant; this.file = file; this.stream = stream;
        }
    }

    private static final class Picker {
        final int code = REQUEST_CODES.getAndIncrement();
        final int epoch;
        final int id;
        final String method;
        final JavaScriptReplyProxy reply;
        final ValueCallback<Uri[]> upload;
        final boolean writable;
        final String download;
        final String cookies;
        final String userAgent;
        Picker(int epoch, int id, String method, JavaScriptReplyProxy reply,
                ValueCallback<Uri[]> upload, boolean writable, String download, String cookies, String userAgent) {
            this.epoch = epoch; this.id = id; this.method = method; this.reply = reply;
            this.upload = upload; this.writable = writable; this.download = download;
            this.cookies = cookies; this.userAgent = userAgent;
        }
    }

    FileBridge(Activity activity) { this.activity = activity; }

    private static JSONObject json(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
        return value;
    }

    private void reply(Picker request, Object value, String name, String message) {
        respond(request.reply, request.id, request.epoch, value, name, message);
    }

    private void respond(JavaScriptReplyProxy reply, int id, int epoch, Object value, String name, String message) {
        main.post(() -> {
            if (destroyed || epoch != generation || reply == null) return;
            reply.postMessage((name == null ? json("id", id, "result", value) :
                    json("id", id, "error", json("name", name, "message", message))).toString());
        });
    }

    void receive(String raw, JavaScriptReplyProxy reply) {
        if (destroyed) return;
        if (raw == null) { notifyError("Invalid file request."); return; }
        final JSONObject request;
        try { request = new JSONObject(raw); }
        catch (JSONException error) { notifyError("Invalid file request."); return; }
        int id = request.optInt("id");
        int epoch = generation;
        if (raw.length() > 65536) {
            respond(reply, id, epoch, null, "TypeError", "File message is too large.");
            return;
        }
        String method = request.optString("method");
        if (method.startsWith("pick")) {
            choose(request, reply, id);
            return;
        }
        if ("notify".equals(method)) { notifyError(request.optString("message")); return; }
        if ("download".equals(method)) {
            download(request.optString("url"), android.webkit.WebSettings.getDefaultUserAgent(activity),
                    null, request.optString("mime", "application/octet-stream"), request.optString("name"));
            return;
        }
        if ("permission".equals(method)) {
            worker.execute(() -> {
                if (destroyed || epoch != generation) return;
                try {
                    Grant grant = grant(request);
                    JSONObject info = metadata(grant);
                    main.post(() -> {
                        if (destroyed || epoch != generation) return;
                        if (permissionDialog != null) {
                            respond(reply, id, epoch, null, "InvalidStateError", "Another file permission request is open.");
                            return;
                        }
                        permissionDialog = new AlertDialog.Builder(activity).setTitle("Allow file changes?")
                                .setMessage("Allow this page to change \"" + info.optString("name") +
                                        "\"? For a folder, this includes files inside it.")
                                .setPositiveButton("Allow", (dialog, which) -> {
                                    if (!destroyed && epoch == generation) grant.writable = true;
                                    respond(reply, id, epoch, true, null, null);
                                })
                                .setNegativeButton("Deny", (dialog, which) ->
                                        respond(reply, id, epoch, false, null, null))
                                .setOnCancelListener(dialog -> respond(reply, id, epoch, false, null, null))
                                .create();
                        permissionDialog.setOnDismissListener(dialog -> permissionDialog = null);
                        permissionDialog.show();
                    });
                } catch (IOException | JSONException | SecurityException error) {
                    respond(reply, id, epoch, null, "NotAllowedError", error.getMessage());
                }
            });
            return;
        }
        worker.execute(() -> {
            if (destroyed || epoch != generation) return;
            try { respond(reply, id, epoch, execute(method, request, epoch), null, null); }
            catch (SecurityException error) { respond(reply, id, epoch, null, "NotAllowedError", error.getMessage()); }
            catch (UnsupportedOperationException error) { respond(reply, id, epoch, null, "NotSupportedError", error.getMessage()); }
            catch (IllegalArgumentException | JSONException error) { respond(reply, id, epoch, null, "TypeError", error.getMessage()); }
            catch (IllegalStateException error) { respond(reply, id, epoch, null, "InvalidStateError", error.getMessage()); }
            catch (java.io.FileNotFoundException error) { respond(reply, id, epoch, null, "NotFoundError", error.getMessage()); }
            catch (IOException error) { respond(reply, id, epoch, null, "UnknownError", error.getMessage()); }
        });
    }

    private Intent documentIntent(String action, String mime) {
        Intent intent = new Intent(action);
        if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
        }
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return intent;
    }

    private void choose(JSONObject request, JavaScriptReplyProxy reply, int id) {
        String method = request.optString("method");
        if (!"pickOpen".equals(method) && !"pickSave".equals(method) && !"pickDirectory".equals(method)) {
            respond(reply, id, generation, null, "NotSupportedError", "Unknown picker.");
            return;
        }
        boolean directory = "pickDirectory".equals(method);
        Intent intent = documentIntent(directory ? Intent.ACTION_OPEN_DOCUMENT_TREE :
                "pickSave".equals(method) ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT,
                request.optString("mime", "*/*"));
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, "pickOpen".equals(method) && request.optBoolean("multiple"));
        intent.putExtra(Intent.EXTRA_TITLE, request.optString("suggestedName", "download"));
        JSONArray types = request.optJSONArray("mimeTypes");
        if (types != null && types.length() > 0) {
            String[] mimes = new String[types.length()];
            for (int i = 0; i < mimes.length; i++) mimes[i] = types.optString(i);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        }
        launch(intent, new Picker(generation, id, method, reply, null,
                !"pickOpen".equals(method) && (!directory || "readwrite".equals(request.optString("mode"))),
                null, null, null));
    }

    boolean upload(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        Intent intent = documentIntent(Intent.ACTION_OPEN_DOCUMENT, "*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        String[] types = params.getAcceptTypes();
        // Extensions are accepted by HTML but not by Android's MIME filter.
        java.util.List<String> mimes = new java.util.ArrayList<>();
        for (String type : types) {
            if (type.contains("/")) mimes.add(type);
            else if (type.startsWith(".")) {
                String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                if (mime != null) mimes.add(mime);
            }
        }
        if (!mimes.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        launch(intent, new Picker(generation, 0, "upload", null, callback, false, null, null, null));
        return true;
    }

    void download(String url, String userAgent, String disposition, String mime) {
        download(url, userAgent, disposition, mime, null);
    }

    private void download(String url, String userAgent, String disposition, String mime, String name) {
        if (!"https".equalsIgnoreCase(Uri.parse(url).getScheme())) {
            notifyError("Only HTTPS, blob, and data downloads are supported.");
            return;
        }
        Intent intent = documentIntent(Intent.ACTION_CREATE_DOCUMENT, mime);
        intent.putExtra(Intent.EXTRA_TITLE, name == null || name.isEmpty() ?
                android.webkit.URLUtil.guessFileName(url, disposition, mime) : name);
        launch(intent, new Picker(generation, 0, "download", null, null, true, url,
                CookieManager.getInstance().getCookie(url), userAgent));
    }

    private void launch(Intent intent, Picker request) {
        if (picker != null) {
            if (request.upload != null) request.upload.onReceiveValue(null);
            else if (request.reply != null) reply(request, null, "InvalidStateError", "Another file picker is open.");
            else notifyError("Another file picker is open.");
            return;
        }
        picker = request;
        try { activity.startActivityForResult(intent, request.code); }
        catch (ActivityNotFoundException | SecurityException error) {
            picker = null;
            if (request.upload != null) request.upload.onReceiveValue(null);
            reply(request, null, "NotSupportedError", "Cannot open the system file picker: " + error.getMessage());
            notifyError("Cannot open the system file picker: " + error.getMessage());
        }
    }

    boolean onActivityResult(int code, int result, Intent data) {
        Picker request = picker;
        if (request == null || request.code != code) return false;
        picker = null;
        if (request.epoch != generation || destroyed) return true;
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (result == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
        }
        if (request.upload != null) {
            request.upload.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            return true;
        }
        if (uris.isEmpty()) { reply(request, null, "AbortError", "File selection cancelled."); return true; }
        worker.execute(() -> {
            if (destroyed || request.epoch != generation) return;
            try {
                if (request.download != null) {
                    downloadTo(request, uris.get(0));
                    main.post(() -> { if (!destroyed && request.epoch == generation) notifyError("Download saved."); });
                    return;
                }
                JSONArray handles = new JSONArray();
                for (Uri selected : uris) {
                    if ("pickDirectory".equals(request.method)) selected = DocumentsContract.buildDocumentUriUsingTree(
                            selected, DocumentsContract.getTreeDocumentId(selected));
                    handles.put(register(new Grant(selected, request.writable)));
                }
                reply(request, "pickOpen".equals(request.method) ? handles : handles.getJSONObject(0), null, null);
            } catch (IOException | JSONException | IllegalArgumentException | SecurityException error) {
                reply(request, null, "NotAllowedError", error.getMessage());
                if (request.download != null) notifyError("Download failed: " + error.getMessage());
            }
        });
        return true;
    }

    private JSONObject metadata(Grant grant) throws IOException {
        try (Cursor cursor = activity.getContentResolver().query(grant.uri, new String[] {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED }, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new java.io.FileNotFoundException("Selected document no longer exists.");
            String mime = cursor.getString(1);
            return json("name", cursor.getString(0), "kind",
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? "directory" : "file",
                    "type", mime == null ? "" : mime, "size", cursor.getLong(2), "lastModified", cursor.getLong(3),
                    "writable", grant.writable, "key", grant.uri.getAuthority() + ":" +
                            DocumentsContract.getDocumentId(grant.uri));
        }
    }

    private JSONObject register(Grant grant) throws IOException {
        JSONObject info = metadata(grant);
        try {
            String key = info.getString("key");
            String token = tokens.get(key);
            if (token == null) {
                token = UUID.randomUUID().toString();
                tokens.put(key, token);
                grants.put(token, grant);
            } else {
                Grant existing = grants.get(token);
                existing.writable |= grant.writable;
                info.put("writable", existing.writable);
            }
            info.put("id", token);
        }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
        return info;
    }

    private Grant grant(JSONObject request) throws JSONException {
        Grant grant = grants.get(request.getString("handle"));
        if (grant == null) throw new SecurityException("Select this file or folder first.");
        return grant;
    }

    private static String childName(JSONObject request) throws JSONException {
        String name = request.getString("name");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name) || name.contains("/") ||
                name.contains("\\") || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid child name.");
        return name;
    }

    private Object execute(String method, JSONObject request, int epoch) throws IOException, JSONException {
        if ("read".equals(method)) {
            InputStream stream = readers.get(request.getString("reader"));
            if (stream == null) throw new IllegalStateException("File reader is closed.");
            byte[] bytes = new byte[CHUNK];
            int count = stream.read(bytes);
            if (count == -1) return JSONObject.NULL;
            return Base64.encodeToString(bytes, 0, count, Base64.NO_WRAP);
        }
        if ("closeRead".equals(method)) {
            InputStream stream = readers.remove(request.getString("reader"));
            if (stream != null) stream.close();
            return JSONObject.NULL;
        }
        if ("write".equals(method) || "seek".equals(method) || "truncate".equals(method) ||
                "commit".equals(method) || "abort".equals(method)) {
            String token = request.getString("writer");
            Write write = writers.get(token);
            if (write == null) throw new IllegalStateException("File writer is closed.");
            switch (method) {
                case "write":
                    byte[] bytes = Base64.decode(request.getString("data"), Base64.NO_WRAP);
                    if (bytes.length > CHUNK) throw new IllegalArgumentException("Write exceeds chunk size.");
                    write.stream.write(bytes);
                    break;
                case "seek": write.stream.seek(position(request)); break;
                case "truncate":
                    long size = position(request);
                    write.stream.setLength(size);
                    if (write.stream.getFilePointer() > size) write.stream.seek(size);
                    break;
                case "commit":
                case "abort":
                    writers.remove(token);
                    try {
                        write.stream.close();
                        if ("commit".equals(method)) {
                            try (InputStream input = new FileInputStream(write.file);
                                    OutputStream output = activity.getContentResolver().openOutputStream(write.grant.uri, "wt")) {
                                if (output == null) throw new IOException("Cannot open selected file for writing.");
                                copy(input, output, epoch);
                            }
                        }
                    } finally { deleteTemp(write.file); }
                    break;
                default: throw new UnsupportedOperationException("Unknown writer operation.");
            }
            return JSONObject.NULL;
        }
        Grant grant = grant(request);
        if ("stat".equals(method)) return metadata(grant);
        if ("openRead".equals(method)) {
            InputStream input = activity.getContentResolver().openInputStream(grant.uri);
            if (input == null) throw new IOException("Cannot read selected file.");
            String token = UUID.randomUUID().toString();
            readers.put(token, input);
            return token;
        }
        if ("openWrite".equals(method)) {
            if (!grant.writable) throw new SecurityException("This handle was granted read-only access. Select it with a save picker or a readwrite folder picker.");
            for (Write existing : writers.values()) {
                if (existing.grant.uri.equals(grant.uri)) throw new IllegalStateException("File already has an open writer.");
            }
            File file = File.createTempFile("web-write-", ".tmp", activity.getCacheDir());
            try {
                if (request.optBoolean("keepExistingData")) {
                    try (InputStream input = activity.getContentResolver().openInputStream(grant.uri);
                            OutputStream output = new java.io.FileOutputStream(file)) {
                        if (input == null) throw new IOException("Cannot read existing file.");
                        copy(input, output, epoch);
                    }
                }
                String token = UUID.randomUUID().toString();
                writers.put(token, new Write(grant, file, new RandomAccessFile(file, "rw")));
                return token;
            } catch (IOException | RuntimeException error) { deleteTemp(file); throw error; }
        }
        if ("entries".equals(method) || "child".equals(method) || "remove".equals(method)) {
            if (!"directory".equals(metadata(grant).getString("kind"))) throw new IllegalArgumentException("Not a directory.");
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(grant.uri,
                    DocumentsContract.getDocumentId(grant.uri));
            String name = "entries".equals(method) ? null : childName(request);
            JSONArray entries = new JSONArray();
            try (Cursor cursor = activity.getContentResolver().query(children, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null, null, null)) {
                if (cursor == null) throw new IOException("Cannot list selected folder.");
                while (cursor.moveToNext()) {
                    if (name != null && !name.equals(cursor.getString(1))) continue;
                    Grant child = new Grant(DocumentsContract.buildDocumentUriUsingTree(grant.uri, cursor.getString(0)), grant.writable);
                    if ("remove".equals(method)) {
                        if (!grant.writable) throw new SecurityException("Folder is read-only.");
                        if ("directory".equals(metadata(child).getString("kind"))) {
                            throw new UnsupportedOperationException("Removing folders is not supported.");
                        }
                        if (!DocumentsContract.deleteDocument(activity.getContentResolver(), child.uri)) throw new IOException("Could not remove file.");
                        return JSONObject.NULL;
                    }
                    JSONObject info = register(child);
                    if (name != null) return info;
                    entries.put(info);
                }
            }
            if (name == null) return entries;
            if ("child".equals(method) && request.optBoolean("create")) {
                if (!grant.writable) throw new SecurityException("Folder is read-only.");
                Uri created = DocumentsContract.createDocument(activity.getContentResolver(), grant.uri,
                        "directory".equals(request.optString("kind")) ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream", name);
                if (created == null) throw new IOException("Could not create document.");
                return register(new Grant(created, true));
            }
            throw new java.io.FileNotFoundException("No entry named " + name);
        }
        throw new UnsupportedOperationException("Unsupported file operation: " + method);
    }

    private static long position(JSONObject request) throws JSONException {
        double value = request.getDouble("position");
        if (!Double.isFinite(value) || value < 0 || value > 9007199254740991d || value != Math.floor(value)) {
            throw new IllegalArgumentException("Invalid file position.");
        }
        return (long) value;
    }

    private void copy(InputStream input, OutputStream output, int epoch) throws IOException {
        byte[] buffer = new byte[CHUNK];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (destroyed || epoch != generation) throw new IOException("Document was closed.");
            output.write(buffer, 0, count);
        }
    }

    private void downloadTo(Picker request, Uri target) throws IOException {
        URL url = new URL(request.download);
        String initialOrigin = MainActivity.httpsOrigin(request.download);
        for (int redirects = 0; redirects <= 10; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Non-HTTPS download redirect blocked.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (request.userAgent != null) connection.setRequestProperty("User-Agent", request.userAgent);
            if (initialOrigin.equals(MainActivity.httpsOrigin(url.toString())) && request.cookies != null) {
                connection.setRequestProperty("Cookie", request.cookies);
            }
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Download redirect has no location.");
                    url = new URL(url, location);
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("Download returned HTTP " + status);
                try (InputStream input = connection.getInputStream();
                        OutputStream output = activity.getContentResolver().openOutputStream(target, "wt")) {
                    if (output == null) throw new IOException("Cannot write selected download.");
                    copy(input, output, request.epoch);
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many download redirects.");
    }

    private void notifyError(String message) {
        main.post(() -> { if (!destroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); });
    }

    private void deleteTemp(File file) {
        if (!file.delete() && file.exists()) notifyError("Could not remove temporary file: " + file.getName());
    }

    void resetDocument() {
        generation++;
        if (permissionDialog != null) permissionDialog.dismiss();
        if (picker != null) {
            if (picker.upload != null) picker.upload.onReceiveValue(null);
            picker = null;
        }
        worker.execute(() -> {
            for (InputStream input : readers.values()) {
                try { input.close(); } catch (IOException error) { notifyError("File cleanup failed: " + error.getMessage()); }
            }
            readers.clear();
            for (Write write : writers.values()) {
                try { write.stream.close(); } catch (IOException error) { notifyError("File cleanup failed: " + error.getMessage()); }
                deleteTemp(write.file);
            }
            writers.clear();
            grants.clear();
            tokens.clear();
        });
    }

    void destroy() {
        resetDocument();
        destroyed = true;
        worker.shutdown();
    }
}
