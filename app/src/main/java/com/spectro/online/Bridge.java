package com.spectro.online;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Jembatan JavaScript <-> Android. Dipanggil dari shim (assets/public, disisipkan ke tiap HTML)
 * lewat window.AndroidBridge.call(id, metode, json). Hasil dikirim balik asinkron ke
 * window.top.__nbDone(id, ok, hasilJson).
 */
public class Bridge {
    private static final String NOTIF_PERM = "android.permission.POST_NOTIFICATIONS";
    private static final String CHANNEL = "spectro_notif";

    private final MainActivity act;
    private final WebView web;
    private WebView printView;
    private String pendingPermId;

    Bridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    /* ---------- pintu masuk dari JS ---------- */
    @JavascriptInterface
    public void call(final String id, final String method, final String json) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = new JSONObject(json == null || json.length() == 0 ? "{}" : json);
                    if ("saveFile".equals(method)) ok(id, saveFile(a));
                    else if ("writePrivate".equals(method)) ok(id, writePrivate(a));
                    else if ("readPrivate".equals(method)) ok(id, readPrivate(a));
                    else if ("printHtml".equals(method)) printHtml(id, a);
                    else if ("notifCheck".equals(method)) ok(id, perm(false));
                    else if ("notifRequest".equals(method)) requestNotif(id);
                    else if ("notify".equals(method)) ok(id, showNotif(a));
                    else if ("clipRead".equals(method)) clipRead(id);
                    else if ("clipWrite".equals(method)) clipWrite(id, a);
                    else fail(id, "Metode tidak dikenal: " + method);
                } catch (Exception e) {
                    fail(id, String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    /* ---------- balasan ke JS ---------- */
    private void reply(final String id, final boolean good, final String payload) {
        final String js = "window.__nbDone&&window.__nbDone(" + JSONObject.quote(id) + "," + good + ","
                + JSONObject.quote(payload) + ")";
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try { web.evaluateJavascript(js, null); } catch (Exception e) { /* abaikan */ }
            }
        });
    }

    private void ok(String id, JSONObject o) {
        reply(id, true, o == null ? "{}" : o.toString());
    }

    private void fail(String id, String msg) {
        reply(id, false, msg);
    }

    /* ---------- simpan ke folder Download ---------- */
    private JSONObject saveFile(JSONObject a) throws Exception {
        String name = a.optString("name", "file.bin");
        String mime = a.optString("mime", "application/octet-stream");
        String data = a.optString("data", "");
        if (data.length() == 0) throw new Exception("Data kosong");
        byte[] bytes = Base64.decode(data, Base64.DEFAULT);
        String where;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = act.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Gagal membuat file di Download");
            OutputStream os = r.openOutputStream(uri);
            if (os == null) throw new Exception("Gagal menulis file");
            try { os.write(bytes); os.flush(); } finally { os.close(); }
            where = "Download/" + name;
        } else {
            File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new Exception("Penyimpanan tidak tersedia");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(bytes); fos.flush(); } finally { fos.close(); }
            where = f.getAbsolutePath();
        }
        return new JSONObject().put("path", where);
    }

    /* ---------- penyimpanan privat app ---------- */
    private File privFile(String name) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new Exception("Nama file tidak valid");
        }
        File dir = new File(act.getFilesDir(), "data");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private JSONObject writePrivate(JSONObject a) throws Exception {
        File f = privFile(a.optString("name", ""));
        byte[] bytes = Base64.decode(a.optString("data", ""), Base64.DEFAULT);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try { fos.write(bytes); fos.flush(); } finally { fos.close(); }
        if (!tmp.renameTo(f)) throw new Exception("Gagal menyimpan file");
        return new JSONObject();
    }

    private JSONObject readPrivate(JSONObject a) throws Exception {
        File f = privFile(a.optString("name", ""));
        JSONObject ret = new JSONObject();
        if (!f.exists()) return ret;
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally { in.close(); }
        ret.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        return ret;
    }

    /* ---------- cetak lewat dialog cetak Android ---------- */
    private void printHtml(final String id, JSONObject a) {
        final String html = a.optString("html", "");
        final String name = a.optString("name", "Spectro");
        final boolean landscape = a.optBoolean("landscape", false);
        final boolean noMargins = a.optBoolean("noMargins", false);
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final WebView wv = new WebView(act);
                    wv.setWebViewClient(new WebViewClient() {
                        private boolean done = false;

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            if (done) return;
                            done = true;
                            try {
                                PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                                PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(name);
                                PrintAttributes.MediaSize size = PrintAttributes.MediaSize.ISO_A4;
                                if (landscape) size = size.asLandscape();
                                PrintAttributes.Builder b = new PrintAttributes.Builder().setMediaSize(size);
                                if (noMargins) b.setMinMargins(PrintAttributes.Margins.NO_MARGINS);
                                pm.print(name, adapter, b.build());
                                ok(id, null);
                            } catch (Exception e) {
                                fail(id, "Gagal membuka dialog cetak: " + e.getMessage());
                            }
                        }
                    });
                    printView = wv;
                    wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
                } catch (Exception e) {
                    fail(id, "Gagal menyiapkan cetak: " + e.getMessage());
                }
            }
        });
    }

    /* ---------- notifikasi ---------- */
    private boolean granted() {
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean enabled = nm != null && nm.areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 33) {
            return enabled && act.checkSelfPermission(NOTIF_PERM) == PackageManager.PERMISSION_GRANTED;
        }
        return enabled;
    }

    private JSONObject perm(boolean asked) throws Exception {
        String d = granted() ? "granted" : (asked || Build.VERSION.SDK_INT < 33 ? "denied" : "prompt");
        return new JSONObject().put("display", d);
    }

    private void requestNotif(final String id) throws Exception {
        if (granted() || Build.VERSION.SDK_INT < 33) {
            ok(id, perm(true));
            return;
        }
        pendingPermId = id;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.requestPermissions(new String[]{NOTIF_PERM}, MainActivity.REQ_NOTIF);
            }
        });
    }

    void onNotifPermissionResult() {
        String id = pendingPermId;
        pendingPermId = null;
        if (id == null) return;
        try { ok(id, perm(true)); } catch (Exception e) { fail(id, String.valueOf(e.getMessage())); }
    }

    private JSONObject showNotif(JSONObject a) throws Exception {
        if (!granted()) throw new Exception("Izin notifikasi belum diberikan");
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Spectro Online",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(act, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(act, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(act, CHANNEL)
                : new Notification.Builder(act);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(a.optString("title", "Spectro Online"))
                .setContentText(a.optString("body", ""))
                .setStyle(new Notification.BigTextStyle().bigText(a.optString("body", "")))
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(a.optInt("id", 1), b.build());
        return new JSONObject();
    }

    /* ---------- clipboard (harus di thread UI) ---------- */
    private void clipRead(final String id) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    String v = "";
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
                        if (t != null) v = t.toString();
                    }
                    ok(id, new JSONObject().put("value", v));
                } catch (Exception e) {
                    fail(id, String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void clipWrite(final String id, final JSONObject a) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("text", a.optString("string", "")));
                    ok(id, null);
                } catch (Exception e) {
                    fail(id, String.valueOf(e.getMessage()));
                }
            }
        });
    }
}
