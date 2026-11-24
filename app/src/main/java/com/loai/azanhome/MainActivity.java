package com.loai.azanhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
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

    /** جسر بين الصفحة والتطبيق: يُعرَّف في الصفحة باسم window.HomeBridge. */
    public class HomeBridge {
        /** يفتح لانشر الشاشة الأصلي (أول لانشر في الجهاز غير هذا التطبيق). */
        @JavascriptInterface
        public void exitToLauncher() {
            runOnUiThread(new Runnable() {
                @Override public void run() { openOtherHome(); }
            });
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
