package com.spectro.online;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebView penuh tanpa dependensi. Isi aplikasi ada di assets/public dan disajikan
 * lewat alamat https://localhost/ supaya localStorage, fetch, dan clipboard bekerja
 * seperti di web biasa.
 */
public class MainActivity extends Activity {

    static final String HOST = "localhost";
    static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 4101;
    static final int REQ_NOTIF = 4102;

    private WebView web;
    private Bridge bridge;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "AndroidBridge");

        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChrome());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                openExternal(url);
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try { web.saveState(out); } catch (Exception e) { /* abaikan */ }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // timer/koneksi halaman dibiarkan jalan saat layar mati sebentar; hanya sinkronkan cookie
        try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception e) { /* abaikan */ }
    }

    @Override
    protected void onDestroy() {
        try { web.destroy(); } catch (Exception e) { /* abaikan */ }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] r = null;
                if (res == RESULT_OK) {
                    r = WebChromeClient.FileChooserParams.parseResult(res, data);
                }
                fileCallback.onReceiveValue(r);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_NOTIF) {
            bridge.onNotifPermissionResult();
            return;
        }
        super.onRequestPermissionsResult(req, perms, results);
    }

    void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) { /* tidak ada aplikasi yang bisa membuka */ }
    }

    /* ---------- menyajikan assets/public sebagai https://localhost/ ---------- */

    private static final Map<String, String> MIME = new HashMap<String, String>();
    static {
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("mjs", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("txt", "text/plain");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("wasm", "application/wasm");
    }

    private WebResourceResponse notFound() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", h,
                new ByteArrayInputStream(new byte[0]));
    }

    WebResourceResponse serve(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.length() == 0 || path.equals("/")) path = "/index.html";
        path = path.substring(1);
        if (path.contains("..")) return notFound();
        InputStream in;
        try {
            in = getAssets().open("public/" + path);
        } catch (IOException e) {
            return notFound();
        }
        String ext = "";
        int dot = path.lastIndexOf('.');
        if (dot >= 0) ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(ext);
        if (mime == null) mime = "application/octet-stream";
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-cache");
        boolean text = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")
                || mime.endsWith("xml") || mime.endsWith("manifest+json");
        return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", h, in);
    }

    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) {
                return serve(u);
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String sch = u.getScheme();
            if (("https".equals(sch) && HOST.equals(u.getHost())) || "blob".equals(sch)
                    || "about".equals(sch) || "data".equals(sch) || "javascript".equals(sch)) {
                return false;
            }
            openExternal(u.toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }
    }

    private class AppChrome extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { result.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.cancel(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { result.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String def, final JsPromptResult result) {
            final EditText et = new EditText(MainActivity.this);
            et.setText(def == null ? "" : def);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setView(et)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.confirm(et.getText().toString()); }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.cancel(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { result.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = cb;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                cb.onReceiveValue(null);
                return false;
            }
            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            return super.onConsoleMessage(m);
        }
    }
}
