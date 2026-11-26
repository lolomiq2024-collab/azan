package com.loai.azanhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Azan Home — يعرض الصفحة المدمجة (assets/index.html) بملء الشاشة.
 * بث IPTV/HLS يُشغَّل عبر ExoPlayer (Media3) خلف الـ WebView الشفاف.
 * زر الرجوع وزر Home لا يُخرجان من التطبيق (وضع كشك)،
 * والخروج للانشر الأصلي للشاشة يتم فقط من إعدادات التطبيق (العودة إلى الشاشة).
 * By Loai.miqdadi
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/index.html";
    private WebView web;
    private ExoBridge exo;
    private static final int REQ_PLAYER = 10;
    private static final int REQ_HOME_ROLE = 11;
    private boolean playerOpen = false;
    private long roleAskedAt = 0;

    /** جسر بين الصفحة والتطبيق: يُعرَّف في الصفحة باسم window.HomeBridge. */
    public class HomeBridge {
        /** يفتح لانشر الشاشة الأصلي (أول لانشر في الجهاز غير هذا التطبيق). */
        @JavascriptInterface
        public void exitToLauncher() {
            runOnUiThread(new Runnable() {
                @Override public void run() { openOtherHome(); }
            });
        }

        /** يجلب نص رابط (قوائم M3U) مباشرة من الشبكة خارج الـ WebView، ويعيده للصفحة عبر window.__nf(id, text).
         *  عند فشل شهادة HTTPS (أجهزة قديمة) يعيد المحاولة مرة واحدة بقبول الشهادة لهذا الاتصال فقط. */
        @JavascriptInterface
        public void fetchText(final String url, final int id) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String out = null;
                    try {
                        out = httpGet(url, false);
                    } catch (javax.net.ssl.SSLException e) {
                        try { out = httpGet(url, true); } catch (Throwable ignored) { }
                    } catch (Throwable ignored) {
                    }
                    final String res = out;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (web == null) return;
                            String js = "window.__nf(" + id + ","
                                    + (res == null ? "null" : org.json.JSONObject.quote(res)) + ");";
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }

        private String httpGet(String url, boolean lenient) throws Exception {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                if (lenient && c instanceof javax.net.ssl.HttpsURLConnection) {
                    javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] x, String a) { }
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] x, String a) { }
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    }};
                    javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                    ctx.init(null, tm, new java.security.SecureRandom());
                    javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) c;
                    hc.setSSLSocketFactory(ctx.getSocketFactory());
                    hc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                        @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                    });
                }
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AzanHome");
                if (c.getResponseCode() != 200) return null;
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < 30000000) {
                    bo.write(buf, 0, n);
                    total += n;
                }
                in.close();
                return bo.toString("UTF-8");
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** يشغّل قناة بث مباشر في المشغّل المستقل. urlsJson: مصفوفة روابط. */
        @JavascriptInterface
        public void playLive(final String title, final String urlsJson) {
            runOnUiThread(() -> openPlayer(title, urlsJson));
        }

        /** يغلق المشغّل (مثلاً عند الأذان). */
        @JavascriptInterface
        public void stopVideo() {
            runOnUiThread(() -> { if (playerOpen) finishActivity(REQ_PLAYER); });
        }

        @JavascriptInterface
        public boolean isDefaultLauncher() {
            return isDefaultHome();
        }

        @JavascriptInterface
        public void setAsLauncher() {
            runOnUiThread(() -> requestDefaultHome());
        }

        @JavascriptInterface
        public boolean canStartOnBoot() {
            return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void allowStartOnBoot() {
            runOnUiThread(() -> requestOverlayPermission());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050A18);

        // طبقة الفيديو (فوق الصفحة)
        SurfaceView surface = new SurfaceView(this);
        surface.setZOrderOnTop(true);      // الفيديو فوق الصفحة (أضمن على أجهزة التلفاز)
        surface.setFocusable(false);       // يبقى التحكم بالريموت للصفحة
        surface.setVisibility(View.GONE);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);   // شفاف كي يظهر الفيديو من خلفه عند التشغيل
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        exo = new ExoBridge(this, root, surface);
        exo.setWeb(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // حفظ الإعدادات (localStorage)
        s.setMediaPlaybackRequiresUserGesture(false); // تشغيل الصوت/الفيديو بدون لمس
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);  // الطقس والبث من الإنترنت
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(exo, "ExoBridge");
        web.addJavascriptInterface(new HomeBridge(), "HomeBridge");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // يجب إزالة الـ WebView الميت وتدميره قبل إعادة التشغيل
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                web = null;
                recreate();   // إن انهارت عملية العرض نعيد التشغيل بدل الإغلاق
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // إخفاء عنصر <video> القديم كي يظهر فيديو ExoPlayer من خلف الصفحة
                view.evaluateJavascript(
                        "(function(){var s=document.createElement('style');"
                                + "s.textContent='html.exo #vid{display:none!important}';"
                                + "document.head.appendChild(s);})();", null);
            }
        });

        web.loadUrl(START_URL);
        web.requestFocus();
    }

    /** يفتح أول لانشر في الجهاز غير هذا التطبيق. إن لم يوجد يبقى التطبيق ظاهراً. */
    private void openOtherHome() {
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(probe, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) {
                    continue;
                }
                if ("com.android.settings".equals(ri.activityInfo.packageName)) {
                    continue;   // شاشة احتياطية للنظام وليست لانشر
                }
                Intent go = new Intent(Intent.ACTION_MAIN);
                go.addCategory(Intent.CATEGORY_HOME);
                go.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(go);
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private void openPlayer(String title, String urlsJson) {
        if (playerOpen) return;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        i.putExtra(PlayerActivity.EXTRA_URLS, urlsJson);
        try {
            startActivityForResult(i, REQ_PLAYER);
            playerOpen = true;
        } catch (Exception e) {
            toast("تعذّر فتح المشغّل");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAYER) {
            if (resultCode == PlayerActivity.RESULT_USE_WEB) {
                String error = data == null ? null : data.getStringExtra(PlayerActivity.EXTRA_ERROR);
                if (error != null && !error.isEmpty()) {
                    toast("المشغّل المدمج: " + error + "\nجارٍ تجربة WebView");
                }
                js("if(window.nativeVideoFallback){nativeVideoFallback();}");
            }
        } else if (requestCode == REQ_HOME_ROLE) {
            // بعض الأجهزة ترفض الطلب فوراً دون عرض أي نافذة: نفتح الإعدادات
            if (!isDefaultHome() && SystemClock.elapsedRealtime() - roleAskedAt < 1000) {
                openHomeSettings();
            }
        }
    }

    private boolean isDefaultHome() {
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager()
                .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** يطلب من النظام جعل التطبيق هو الشاشة الرئيسية. */
    private void requestDefaultHome() {
        if (isDefaultHome()) {
            toast("التطبيق هو اللانشر الرئيسي بالفعل");
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    roleAskedAt = SystemClock.elapsedRealtime();
                    startActivityForResult(
                            rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        openHomeSettings();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            toast("هذا الجهاز لا يسمح بتغيير اللانشر من الإعدادات");
        }
    }

    /** إذن "العرض فوق التطبيقات" اللازم للتشغيل عند الإقلاع على أندرويد 10 فما فوق. */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            toast("الإذن مفعّل");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                toast("فعّل الإذن من: الإعدادات ▸ التطبيقات ▸ العرض فوق التطبيقات");
            }
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) {
            web.onResume();
        }
        // العودة من المشغّل. لا نعتمد على onActivityResult: لا يصل دائماً إن ماتت العملية
        if (playerOpen) {
            playerOpen = false;
            // بقاء العلامة يعني أن المشغّل انهار (ويقرأها هو في المرة القادمة)
            if (new File(getFilesDir(), PlayerActivity.CRASH_MARKER).exists()) {
                toast("توقف المشغّل المدمج، جارٍ تجربة WebView");
                js("if(window.nativeVideoCrashed){nativeVideoCrashed();}");
            }
        }
        // تحديث حالة صفوف الإعدادات بعد العودة من إعدادات النظام
        js("if(typeof scr!=='undefined'&&scr==='settings'){renderSettings();}");
    }

    @Override
    protected void onStop() {
        if (exo != null) {
            exo.pauseIfPlaying();
        }
        super.onStop();
    }

    /** زر الرجوع يُمرَّر للصفحة ولا يغلق التطبيق (وضع كشك). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && web != null) {
                web.evaluateJavascript(
                        "if(document.onkeydown){document.onkeydown({keyCode:27,preventDefault:function(){}});}",
                        null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // لا شيء
    }

    @Override
    protected void onDestroy() {
        if (exo != null) {
            exo.release();
            exo = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
