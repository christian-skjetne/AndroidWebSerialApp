package dev.webserial.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DEMO = "https://appassets.androidplatform.net/assets/terminal.html";
    private static final int BACKGROUND = 0xff0c111b;
    private static final int SURFACE = 0xff172131;
    private static final int INK = 0xffe6edf7;
    private static final int MUTED = 0xffa5b4c9;
    private static final int ACCENT = 0xff7de2c3;
    private LinearLayout root;
    private LinearLayout chrome;
    private FrameLayout content;
    private ImageButton restoreControls;
    private ImageButton bookmarkButton;
    private boolean controlsCollapsed;
    private EditText address;
    private TextView status;
    private ProgressBar progress;
    private WebView webView;
    private SerialBridge bridge;
    private FileBridge files;
    private String origin;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(16), dp(10), dp(16), dp(4));
        root.addView(chrome);
        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(4), dp(2), dp(4), dp(2));
        addressRow.setBackground(rounded(SURFACE, 20));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setBackground(null);
        address.setPadding(dp(12), dp(8), dp(8), dp(8));
        address.setSelectAllOnFocus(true);
        address.setContentDescription("Website address");
        address.setHint("Enter an HTTPS address");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageButton go = addTool(addressRow, R.drawable.ic_arrow_forward, "Open website", () -> open(address.getText().toString()));
        go.setImageTintList(ColorStateList.valueOf(BACKGROUND));
        go.setBackground(new RippleDrawable(ColorStateList.valueOf(0x330c111b), rounded(ACCENT, 16), null));
        chrome.addView(addressRow);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addNavigationTool(toolbar, R.drawable.ic_reload, "Reload", "Reload", () -> { if (webView != null) webView.reload(); });
        addNavigationTool(toolbar, R.drawable.ic_terminal, "Terminal", "Local serial terminal", () -> open(DEMO));
        bookmarkButton = addNavigationTool(toolbar, R.drawable.ic_bookmark, "Save", "Bookmark current page", this::saveBookmark);
        addNavigationTool(toolbar, R.drawable.ic_bookmarks, "Saved", "Bookmarks", this::showBookmarks);
        addNavigationTool(toolbar, R.drawable.ic_chevron_up, "Hide", "Hide app controls", () -> setControlsCollapsed(true));
        LinearLayout.LayoutParams toolbarLayout = new LinearLayout.LayoutParams(-1, -2);
        toolbarLayout.topMargin = dp(8);
        chrome.addView(toolbar, toolbarLayout);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE));
        progress.setVisibility(View.INVISIBLE);
        chrome.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(MUTED);
        status.setPadding(dp(4), dp(6), dp(4), dp(6));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        chrome.addView(status);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        restoreControls = createTool(R.drawable.ic_chevron_down, "Show app controls", () -> setControlsCollapsed(false));
        restoreControls.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227de2c3), rounded(SURFACE, 16), null));
        restoreControls.setElevation(dp(6));
        FrameLayout.LayoutParams restoreLayout = new FrameLayout.LayoutParams(dp(48), dp(48),
            android.view.Gravity.TOP | android.view.Gravity.END);
        restoreLayout.setMargins(dp(12), dp(12), dp(12), dp(12));
        content.addView(restoreControls, restoreLayout);
        setContentView(root);
        address.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                open(address.getText().toString());
                return true;
            }
            return false;
        });
        open(getPreferences(MODE_PRIVATE).getString("url", DEMO));
        setControlsCollapsed(getPreferences(MODE_PRIVATE).getBoolean("controlsCollapsed", false));
    }

    private ImageButton addTool(LinearLayout toolbar, int icon, String label, Runnable action) {
        ImageButton button = createTool(icon, label, action);
        toolbar.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private ImageButton createTool(int icon, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(INK));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227de2c3),
                rounded(android.graphics.Color.TRANSPARENT, 16), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private ImageButton addNavigationTool(LinearLayout toolbar, int icon, String caption, String label, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        ImageButton button = addTool(item, icon, label, action);
        TextView text = new TextView(this);
        text.setText(caption);
        text.setTextSize(10);
        text.setTextColor(MUTED);
        text.setGravity(android.view.Gravity.CENTER);
        text.setPadding(0, 0, 0, dp(6));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.setOnClickListener(view -> button.performClick());
        item.addView(text);
        toolbar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setControlsCollapsed(boolean collapsed) {
        controlsCollapsed = collapsed;
        chrome.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        restoreControls.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        if (collapsed) {
            address.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean("controlsCollapsed", collapsed).apply();
    }

    private JSONArray bookmarks() {
        try { return new JSONArray(getPreferences(MODE_PRIVATE).getString("bookmarks", "[]")); }
        catch (JSONException error) { return new JSONArray(); }
    }

    private String currentUrl() { return webView == null ? null : webView.getUrl(); }

    private boolean isBookmarked(String url) {
        JSONArray entries = bookmarks();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null && entry.optString("url").equals(url)) return true;
        }
        return false;
    }

    private void updateBookmarkButton() {
        boolean saved = isBookmarked(currentUrl());
        bookmarkButton.setImageResource(saved ? R.drawable.ic_bookmark_saved : R.drawable.ic_bookmark);
        bookmarkButton.setImageTintList(ColorStateList.valueOf(saved ? ACCENT : INK));
        String label = saved ? "Page bookmarked" : "Bookmark current page";
        bookmarkButton.setContentDescription(label);
        bookmarkButton.setTooltipText(label);
    }

    private void saveBookmark() {
        String url = currentUrl();
        if (url == null) { toast("Open a page first."); return; }
        try { httpsOrigin(url); }
        catch (IllegalArgumentException error) { toast("Only HTTPS pages can be bookmarked."); return; }
        if (isBookmarked(url)) { toast("Page already bookmarked."); return; }
        JSONArray entries = bookmarks();
        JSONObject entry = new JSONObject();
        try {
            String title = webView.getTitle();
            entry.put("title", title == null || title.trim().isEmpty() ? url : title);
            entry.put("url", url);
            entries.put(entry);
        } catch (JSONException error) { toast("Could not save bookmark."); return; }
        getPreferences(MODE_PRIVATE).edit().putString("bookmarks", entries.toString()).apply();
        updateBookmarkButton();
        toast("Bookmark saved.");
    }

    private void showBookmarks() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Bookmarks")
                .setView(scroll).setNegativeButton("Close", null).create();
        populateBookmarks(list, dialog);
        dialog.show();
    }

    private void populateBookmarks(LinearLayout list, AlertDialog dialog) {
        list.removeAllViews();
        JSONArray entries = bookmarks();
        if (entries.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Your workspace, one tap away.\nSave a page using the bookmark button.");
            empty.setTextColor(MUTED);
            empty.setTextSize(14);
            empty.setLineSpacing(dp(6), 1);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            list.addView(empty);
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) continue;
            String url = entry.optString("url");
            String title = entry.optString("title", url);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(SURFACE, 16));
            LinearLayout link = new LinearLayout(this);
            link.setOrientation(LinearLayout.VERTICAL);
            link.setPadding(dp(8), dp(14), dp(8), dp(14));
            link.setMinimumHeight(dp(64));
            link.setBackground(new RippleDrawable(ColorStateList.valueOf(0x227de2c3), null, rounded(INK, 16)));
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextColor(INK);
            titleView.setTextSize(15);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setMaxLines(2);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            link.addView(titleView);
            TextView urlView = new TextView(this);
            urlView.setText(url);
            urlView.setTextColor(MUTED);
            urlView.setTextSize(12);
            urlView.setPadding(0, dp(4), 0, 0);
            urlView.setMaxLines(1);
            urlView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            link.addView(urlView);
            link.setOnClickListener(view -> { dialog.dismiss(); open(url); });
            row.addView(link, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton remove = addTool(row, R.drawable.ic_delete, "Remove bookmark: " + title, () -> {
                JSONArray remaining = new JSONArray();
                JSONArray saved = bookmarks();
                for (int savedIndex = 0; savedIndex < saved.length(); savedIndex++) {
                    JSONObject savedEntry = saved.optJSONObject(savedIndex);
                    if (savedEntry != null && !url.equals(savedEntry.optString("url"))) remaining.put(savedEntry);
                }
                getPreferences(MODE_PRIVATE).edit().putString("bookmarks", remaining.toString()).apply();
                updateBookmarkButton();
                populateBookmarks(list, dialog);
            });
            remove.setImageTintList(ColorStateList.valueOf(MUTED));
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, -2);
            rowLayout.topMargin = dp(8);
            list.addView(row, rowLayout);
        }
    }

    static String httpsOrigin(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getHost().isEmpty() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Enter an HTTPS website address.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IllegalArgumentException("Invalid HTTPS port.");
        return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void open(String input) {
        String url = input.trim();
        if (!url.contains("://")) url = "https://" + url;
        final String selectedOrigin;
        try { selectedOrigin = httpsOrigin(url); }
        catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            new AlertDialog.Builder(this).setTitle("WebView update required")
                    .setMessage("Update Android System WebView / Chrome, then reopen this app.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        disposeWebView();
        origin = selectedOrigin;
        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                return files.upload(callback, params);
            }
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value == 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        bridge = new SerialBridge(this, origin);
        files = new FileBridge(this);
        webView.setDownloadListener((urlToSave, userAgent, disposition, mime, length) ->
                files.download(urlToSave, userAgent, disposition, mime));
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                try {
                    if (origin.equals(httpsOrigin(request.getUrl().toString()))) return false;
                } catch (IllegalArgumentException ignored) { }
                toast("Navigation blocked. Open the new HTTPS address from the address bar.");
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bridge.resetDocument();
                files.resetDocument();
                address.setText(url);
                status.setText(DEMO.equals(url) ? "LOCAL WORKSPACE  /  USB serial terminal" :
                        "SITE ACCESS  /  " + origin);
                updateBookmarkButton();
            }
            @Override public void onPageFinished(WebView view, String url) {
                updateBookmarkButton();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) status.setText(error.getDescription());
            }
        });
        WebViewCompat.addWebMessageListener(webView, "AndroidSerial", Collections.singleton(origin),
                (view, message, sourceOrigin, mainFrame, reply) -> {
                    if (mainFrame && origin.equals(httpsOrigin(sourceOrigin.toString()))) {
                        bridge.receive(message.getData(), reply);
                    }
                });
        WebViewCompat.addWebMessageListener(webView, "AndroidFiles", Collections.singleton(origin),
                (view, message, sourceOrigin, mainFrame, reply) -> {
                    if (mainFrame && origin.equals(httpsOrigin(sourceOrigin.toString()))) {
                        files.receive(message.getData(), reply);
                    }
                });
        try {
            for (String asset : new String[] { "serial.js", "files.js" }) {
                String script;
                try (java.io.InputStream stream = getAssets().open(asset)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    script = bytes.toString(StandardCharsets.UTF_8.name());
                }
                WebViewCompat.addDocumentStartJavaScript(webView, script, Collections.singleton(origin));
            }
        } catch (IOException error) {
            toast("Could not load website bridges: " + error.getMessage());
            disposeWebView();
            return;
        }
        content.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
        getPreferences(MODE_PRIVATE).edit().putString("url", url).apply();
        webView.loadUrl(url);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void disposeWebView() {
        if (files != null) { files.destroy(); files = null; }
        if (bridge != null) { bridge.destroy(); bridge = null; }
        if (webView != null) {
            content.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    @Override public void onBackPressed() {
        if (controlsCollapsed) setControlsCollapsed(false);
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (files != null) files.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onDestroy() { disposeWebView(); super.onDestroy(); }
}