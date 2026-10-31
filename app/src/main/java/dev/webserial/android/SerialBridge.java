package dev.webserial.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.webkit.JavaScriptReplyProxy;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SerialBridge {
    private final Activity activity;
    private final String origin;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final Map<String, UsbSerialPort> grants = new ConcurrentHashMap<>();
    private final Map<String, UsbSerialPort> opened = new ConcurrentHashMap<>();
    private final Map<Integer, PermissionRequest> permissions = new HashMap<>();
    private final String permissionAction;
    private int permissionSequence;
    private volatile int generation;
    private volatile boolean destroyed;
    private AlertDialog chooser;
    private JavaScriptReplyProxy eventReply;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (permissionAction.equals(intent.getAction())) {
                int token = intent.getIntExtra("token", -1);
                PermissionRequest request = permissions.remove(token);
                if (request == null || request.generation != generation || device == null ||
                        device.getDeviceId() != request.port.getDevice().getDeviceId()) return;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(device)) {
                    grant(request.port, request.id, request.reply, request.generation);
                } else fail(request.reply, request.id, "NotAllowedError", "USB permission denied.", request.generation);
                return;
            }
            if (device == null) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                for (Map.Entry<String, UsbSerialPort> entry : grants.entrySet()) {
                    if (entry.getValue().getDevice().getDeviceId() != device.getDeviceId()) continue;
                    String portId = entry.getKey();
                    grants.remove(portId);
                    commands.execute(() -> closePort(portId));
                    event(json("event", "disconnect", "portId", portId));
                }
            }
        }
    };

    SerialBridge(Activity activity, String origin) {
        this.activity = activity;
        this.origin = origin;
        usb = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        permissionAction = activity.getPackageName() + ".USB_PERMISSION." + java.util.UUID.randomUUID();
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, filter);
    }

    void receive(String raw, JavaScriptReplyProxy reply) {
        if (destroyed || raw == null || raw.length() > 65536) return;
        int epoch = generation;
        JSONObject request;
        try { request = new JSONObject(raw); }
        catch (JSONException error) { return; }
        int id = request.optInt("id", -1);
        if (id < 1) return;
        eventReply = reply;
        String method = request.optString("method");
        if ("requestPort".equals(method)) { choose(request, reply, id, epoch); return; }
        ExecutorService executor = "read".equals(method) ? reads : commands;
        executor.execute(() -> {
            if (destroyed || epoch != generation) return;
            try { respond(reply, id, execute(method, request), epoch); }
            catch (SecurityException error) { fail(reply, id, "NotAllowedError", error.getMessage(), epoch); }
            catch (UnsupportedOperationException error) { fail(reply, id, "NotSupportedError", error.getMessage(), epoch); }
            catch (IllegalArgumentException | JSONException error) { fail(reply, id, "TypeError", error.getMessage(), epoch); }
            catch (IllegalStateException error) { fail(reply, id, "InvalidStateError", error.getMessage(), epoch); }
            catch (IOException error) { fail(reply, id, "NetworkError", error.getMessage(), epoch); }
        });
    }

    private Object execute(String method, JSONObject request) throws IOException, JSONException {
        if ("getPorts".equals(method)) {
            JSONArray result = new JSONArray();
            for (UsbSerialPort port : grants.values()) result.put(info(port));
            return result;
        }
        String portId = request.getString("portId");
        UsbSerialPort port = grants.get(portId);
        if (port == null) throw new SecurityException("Select this port using requestPort() first.");
        switch (method) {
            case "open": {
                if (opened.containsKey(portId)) throw new IllegalStateException("Port is already open.");
                JSONObject options = request.getJSONObject("options");
                int baudRate = options.getInt("baudRate");
                int dataBits = options.getInt("dataBits");
                int stopBits = options.getInt("stopBits");
                String parityName = options.getString("parity");
                String flow = options.getString("flowControl");
                if (baudRate <= 0 || (dataBits != 7 && dataBits != 8) || (stopBits != 1 && stopBits != 2) ||
                        !Arrays.asList("none", "even", "odd").contains(parityName) ||
                        !Arrays.asList("none", "hardware").contains(flow)) throw new IllegalArgumentException("Invalid serial options.");
                int parity = "even".equals(parityName) ? UsbSerialPort.PARITY_EVEN :
                        "odd".equals(parityName) ? UsbSerialPort.PARITY_ODD : UsbSerialPort.PARITY_NONE;
                UsbDeviceConnection connection = usb.openDevice(port.getDevice());
                if (connection == null) throw new IOException("Cannot open USB device. Reconnect and select it again.");
                try {
                    port.open(connection);
                    port.setParameters(baudRate, dataBits, stopBits, parity);
                    port.setFlowControl("hardware".equals(flow) ? UsbSerialPort.FlowControl.RTS_CTS : UsbSerialPort.FlowControl.NONE);
                    opened.put(portId, port);
                } catch (IOException | RuntimeException error) {
                    try { port.close(); } catch (IOException ignored) { }
                    connection.close();
                    throw error;
                }
                return JSONObject.NULL;
            }
            case "close": closePort(portId); return JSONObject.NULL;
            case "forget":
                if (opened.containsKey(portId)) throw new IllegalStateException("Close the port first.");
                grants.remove(portId);
                return JSONObject.NULL;
            default: break;
        }
        if (!opened.containsKey(portId)) throw new IllegalStateException("Port is not open.");
        switch (method) {
            case "read": {
                byte[] buffer = new byte[16384];
                int count = port.read(buffer, 200);
                return Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP);
            }
            case "write": {
                String encoded = request.getString("data");
                if (encoded.length() > 21848) throw new IllegalArgumentException("Write chunk is too large.");
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                if (bytes.length > 16384) throw new IllegalArgumentException("Write chunk is too large.");
                port.write(bytes, 5000);
                return JSONObject.NULL;
            }
            case "setSignals": {
                JSONObject signals = request.getJSONObject("signals");
                if (signals.has("dataTerminalReady")) port.setDTR(signals.getBoolean("dataTerminalReady"));
                if (signals.has("requestToSend")) {
                    if (port.getFlowControl() == UsbSerialPort.FlowControl.RTS_CTS) {
                        throw new IllegalStateException("RTS is controlled by hardware flow control.");
                    }
                    port.setRTS(signals.getBoolean("requestToSend"));
                }
                if (signals.has("break")) port.setBreak(signals.getBoolean("break"));
                return JSONObject.NULL;
            }
            case "getSignals": {
                java.util.EnumSet<UsbSerialPort.ControlLine> lines = port.getControlLines();
                return json("clearToSend", lines.contains(UsbSerialPort.ControlLine.CTS),
                        "dataSetReady", lines.contains(UsbSerialPort.ControlLine.DSR),
                        "dataCarrierDetect", lines.contains(UsbSerialPort.ControlLine.CD),
                        "ringIndicator", lines.contains(UsbSerialPort.ControlLine.RI));
            }
            default: throw new UnsupportedOperationException("Unknown serial operation.");
        }
    }

    private void choose(JSONObject request, JavaScriptReplyProxy reply, int id, int epoch) {
        if (chooser != null || !permissions.isEmpty()) {
            fail(reply, id, "InvalidStateError", "A USB chooser or permission request is already open.", epoch);
            return;
        }
        List<UsbSerialPort> candidates = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try {
            JSONArray filters = request.optJSONArray("filters");
            if (filters == null) filters = new JSONArray();
            for (int index = 0; index < filters.length(); index++) {
                JSONObject filter = filters.getJSONObject(index);
                if ((filter.has("usbProductId") && !filter.has("usbVendorId")) ||
                        (filter.has("usbVendorId") && (filter.getInt("usbVendorId") < 0 || filter.getInt("usbVendorId") > 65535)) ||
                        (filter.has("usbProductId") && (filter.getInt("usbProductId") < 0 || filter.getInt("usbProductId") > 65535))) {
                    throw new IllegalArgumentException("Invalid USB filter.");
                }
            }
            for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usb)) {
                UsbDevice device = driver.getDevice();
                boolean matches = filters.length() == 0;
                for (int index = 0; index < filters.length(); index++) {
                    JSONObject filter = filters.getJSONObject(index);
                    if ((!filter.has("usbVendorId") || filter.getInt("usbVendorId") == device.getVendorId()) &&
                            (!filter.has("usbProductId") || filter.getInt("usbProductId") == device.getProductId())) matches = true;
                }
                if (!matches) continue;
                for (UsbSerialPort port : driver.getPorts()) {
                    candidates.add(port);
                    labels.add(String.format(java.util.Locale.ROOT, "%s\n%04X:%04X - port %d - device %d",
                            device.getProductName() == null ? driver.getClass().getSimpleName() : device.getProductName(),
                            device.getVendorId(), device.getProductId(), port.getPortNumber(), device.getDeviceId()));
                }
            }
        } catch (JSONException | IllegalArgumentException error) {
            fail(reply, id, "TypeError", error.getMessage(), epoch);
            return;
        }
        if (candidates.isEmpty()) {
            fail(reply, id, "NotFoundError", "No matching USB serial ports. Check the OTG cable and device support.", epoch);
            return;
        }
        chooser = new AlertDialog.Builder(activity).setTitle("Allow USB serial access?\n" + origin)
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    UsbSerialPort port = candidates.get(index);
                    if (usb.hasPermission(port.getDevice())) { grant(port, id, reply, epoch); return; }
                    int token = ++permissionSequence;
                    permissions.put(token, new PermissionRequest(port, id, reply, epoch));
                    Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName()).putExtra("token", token);
                    PendingIntent permission = PendingIntent.getBroadcast(activity, token, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    try { usb.requestPermission(port.getDevice(), permission); }
                    catch (RuntimeException error) {
                        permissions.remove(token);
                        fail(reply, id, "NotAllowedError", error.getMessage(), epoch);
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> fail(reply, id, "NotFoundError", "No port selected.", epoch))
                .setOnCancelListener(dialog -> fail(reply, id, "NotFoundError", "No port selected.", epoch))
                .create();
        chooser.setOnDismissListener(dialog -> chooser = null);
        chooser.show();
    }

    private void grant(UsbSerialPort port, int id, JavaScriptReplyProxy reply, int epoch) {
        if (destroyed || epoch != generation) return;
        grants.putIfAbsent(key(port), port);
        respond(reply, id, info(port), epoch);
    }

    private static String key(UsbSerialPort port) { return port.getDevice().getDeviceId() + ":" + port.getPortNumber(); }

    private static JSONObject info(UsbSerialPort port) {
        return json("id", key(port), "usbVendorId", port.getDevice().getVendorId(), "usbProductId", port.getDevice().getProductId());
    }

    private void closePort(String portId) {
        UsbSerialPort port = opened.remove(portId);
        if (port != null) try { port.close(); } catch (IOException ignored) { }
    }

    void resetDocument() {
        generation++;
        eventReply = null;
        permissions.clear();
        if (chooser != null) chooser.dismiss();
        commands.execute(() -> {
            for (String portId : new ArrayList<>(opened.keySet())) closePort(portId);
        });
    }

    void destroy() {
        if (destroyed) return;
        resetDocument();
        destroyed = true;
        activity.unregisterReceiver(receiver);
        reads.shutdown();
        commands.shutdown();
    }

    private void respond(JavaScriptReplyProxy reply, int id, Object result, int epoch) {
        send(reply, json("id", id, "result", result == null ? JSONObject.NULL : result), epoch);
    }

    private void fail(JavaScriptReplyProxy reply, int id, String name, String message, int epoch) {
        send(reply, json("id", id, "error", json("name", name, "message", message == null ? name : message)), epoch);
    }

    private void event(JSONObject message) {
        if (eventReply != null) send(eventReply, message, generation);
    }

    private void send(JavaScriptReplyProxy reply, JSONObject message, int epoch) {
        main.post(() -> { if (!destroyed && epoch == generation) reply.postMessage(message.toString()); });
    }

    private static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try {
            for (int index = 0; index < pairs.length; index += 2) object.put((String) pairs[index], pairs[index + 1]);
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
        return object;
    }

    private static final class PermissionRequest {
        final UsbSerialPort port;
        final int id;
        final JavaScriptReplyProxy reply;
        final int generation;
        PermissionRequest(UsbSerialPort port, int id, JavaScriptReplyProxy reply, int generation) {
            this.port = port;
            this.id = id;
            this.reply = reply;
            this.generation = generation;
        }
    }
}