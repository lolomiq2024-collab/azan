package com.loai.azanhome;

import android.app.Activity;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * أفلام ومسلسلات: يحمّل صفحة المشغّل المرفقة (assets/movies.html) بملء الشاشة فوق الصفحة الرئيسية.
 * الصفحة تدير القوائم والتشغيل بنفسها، والتطبيق يستدعي أوامرها فقط:
 *   adhanStart() عند بدء الأذان، adhanEnd() عند انتهائه.
 * لها نفس واجهة MoviesPanel القديمة، فتحلّ محلها في MainActivity دون تغيير بقية التطبيق.
 */
final class MoviesPage {

    // هوية التضمين: معرّف حزمة التطبيق (يطلبها يوتيوب لتجنّب الخطأ 152/153)
    private static final String ORIGIN = "https://com.loai.azanhome";

    private final Activity activity;
    private final Runnable onClose;
    private final FrameLayout rootFrame;
    private final WebView web;
    private final WebChromeClient chrome;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean held = false;       // الأذان يعمل الآن
    private boolean destroyed = false;

    MoviesPage(Activity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(Color.BLACK);
        rootFrame.setClickable(true);   // لا تمرّ اللمسات إلى الصفحة التي تحتها

        web = new WebView(activity);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // لا نسمح بالخروج من الصفحة
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                close();   // انهيار عملية العرض: نغلق الصفحة ويبقى التطبيق يعمل
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                rootFrame.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootFrame.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
                web.requestFocus();
            }
        };
        web.setWebChromeClient(chrome);

        rootFrame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String html = readAsset("movies.html");
        if (html == null) {
            html = "<body style='background:#000;color:#fff;font:24px sans-serif;text-align:center'>"
                    + "<p>تعذر فتح صفحة الأفلام</p></body>";
        }
        web.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null);
    }

    private String readAsset(String name) {
        try (InputStream in = activity.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    View view() {
        return rootFrame;
    }

    // ---------- الأذان ----------

    /** بداية الأذان: تتوقف الصفحة عبر adhanStart ثم تُخفى ليظهر الأذان. */
    void hold() {
        if (held || destroyed) return;
        held = true;
        rootFrame.setVisibility(View.GONE);
        final WebView w = web;
        w.evaluateJavascript("window.adhanStart&&adhanStart();", value -> {
            if (held && !destroyed) w.onPause();   // تجميد احتياطي بعد أن يتوقف المشغّل
        });
    }

    /** انتهاء الأذان: تعود الصفحة وتكمل عبر adhanEnd. */
    void release() {
        if (!held || destroyed) return;
        held = false;
        rootFrame.setVisibility(View.VISIBLE);
        web.onResume();
        web.evaluateJavascript("window.adhanEnd&&adhanEnd();", null);
        focus();
    }

    boolean isHeld() {
        return held;
    }

    void focus() {
        if (customView != null) customView.requestFocus();
        else web.requestFocus();
    }

    // ---------- الريموت ----------

    /** رجوع: يخرج من ملء الشاشة أولاً، وإلا يغلق الصفحة ويعود للشاشة الرئيسية. */
    void back() {
        if (customView != null) {
            chrome.onHideCustomView();
        } else {
            close();
        }
    }

    /** أزرار الريموت تصل إلى الصفحة نفسها (تنقّل بين أزرارها)، فلا شيء نعترضه هنا. */
    boolean handleKey(KeyEvent e) {
        return false;
    }

    // ---------- دورة الحياة ----------

    void onPause() {
        if (!destroyed) web.onPause();
    }

    void onResume() {
        if (!destroyed && !held) web.onResume();
    }

    void close() {
        if (destroyed) return;
        destroy();
        onClose.run();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
    }
}
