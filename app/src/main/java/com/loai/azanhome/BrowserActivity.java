package com.loai.azanhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * متصفح داخل التطبيق يعمل بالريموت: مؤشر يتحرك بالأسهم، وفيديو بملء الشاشة.
 *
 * صفحات الإنترنت غير موثوقة، لذلك: لا يوجد أي جسر JavaScript إلى التطبيق،
 * ولا وصول للملفات، ولا تجاوز لأخطاء الشهادات، ولا أذونات للمواقع.
 * على أندرويد 9 فما فوق يعمل في عملية مستقلة (:browser) ببيانات منفصلة.
 */
public class BrowserActivity extends Activity {

    /** وقت الأذان القادم (ميلي ثانية): يُغلق المتصفح عنده كي يظهر الأذان. */
    static final String EXTRA_ADHAN_AT = "adhanAt";

    private static final String PREFS = "browser";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_DESKTOP = "desktop";
    private static final String KEY_ZOOM = "textZoom";
    private static final int MAX_FAVORITES = 30;
    private static final long LONG_BACK_MS = 700;
    private static final int MAX_PAGE_DIALOGS = 3;
    private static final long CURSOR_HIDE_MS = 4000;
    private static final int ACCENT = 0xFF6FB1FF;
    private static final String VIDEO_JS =
            "(function(){var v=document.querySelector('video');if(v){%s}})()";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout page;
    private FrameLayout fullscreen;
    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private TextView starButton;
    private TextView desktopButton;
    private CursorView cursor;

    private SharedPreferences prefs;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private float cursorX;
    private float cursorY;
    private boolean pressing = false;
    private long pressTime;
    private boolean backDown = false;
    /** آخر ما فعله المستخدم كان كتابة (لا تحريك المؤشر): عندها يذهب Enter إلى حقل الكتابة. */
    private boolean typing = false;
    private int pageDialogs = 0;
    private long adhanAt;

    private final Runnable hideCursor = new Runnable() {
        @Override public void run() {
            if (customView != null && !pressing) cursor.setVisibility(View.INVISIBLE);
        }
    };

    private final Runnable adhanWatch = new Runnable() {
        @Override public void run() {
            if (adhanAt > 0 && System.currentTimeMillis() >= adhanAt) {
                finish();
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // يجب أن يسبق إنشاء أي WebView في العملية المستقلة
        if (Build.VERSION.SDK_INT >= 28 && getClass() == BrowserActivity.class) {
            try {
                WebView.setDataDirectorySuffix("browser");
            } catch (IllegalStateException ignored) {
                // ضُبط من قبل في هذه العملية
            }
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        adhanAt = getIntent().getLongExtra(EXTRA_ADHAN_AT, 0);
        try {
            web = new WebView(this);
        } catch (Throwable e) {
            toast("تعذّر تشغيل المتصفح: مكوّن WebView غير متوفر");
            finish();
            return;
        }
        buildViews();
        setupWeb();
        web.loadUrl(BrowserPages.HOME_URL);
        web.requestFocus();
        toast("الأسهم: المؤشر • موافق: نقر • رجوع مطوّل: إغلاق");
    }

    // ---------------------------------------------------------------- الواجهة

    private void buildViews() {
        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(0xFF08101F);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF14213A);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));

        button(bar, "✕", "إغلاق المتصفح", v -> finish());
        button(bar, "←", "السابق", v -> {
            if (web != null && web.canGoBack()) web.goBack();
        });
        button(bar, "→", "التالي", v -> {
            if (web != null && web.canGoForward()) web.goForward();
        });
        button(bar, "↻", "تحديث", v -> {
            if (web != null) web.reload();
        });
        button(bar, "الرئيسية", "صفحة البداية", v -> load(BrowserPages.HOME_URL));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("اكتب عنوان موقع أو كلمة للبحث");
        urlBar.setTextSize(16);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(0xFF8FA3C8);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBar.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setBackground(rounded(0xFF0A1428));
        urlBar.setPadding(dp(14), dp(7), dp(14), dp(7));
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                    String url = BrowserPages.toUrl(urlBar.getText().toString());
                    if (url != null) load(url);
                }
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                hideKeyboard();
                showAddress();
            }
        });
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        urlParams.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(urlBar, urlParams);

        starButton = button(bar, "☆", "المفضلة", v -> toggleFavorite());
        button(bar, "A+", "تكبير الخط", v -> nextZoom());
        desktopButton = button(bar, "كمبيوتر", "نسخة الكمبيوتر من الموقع", v -> toggleDesktop());

        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        page.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, match());

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen, match());

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.LEFT));

        setContentView(root);
        root.post(this::centerCursor);
    }

    private TextView button(LinearLayout bar, String label, String description, View.OnClickListener action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setContentDescription(description);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(42));
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFF34558F));
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(background);
        b.setOnClickListener(action);
        b.setFocusable(false);
        bar.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- WebView

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);   // الروابط ذات النافذة الجديدة تُفتح في نفس الصفحة
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(prefs.getInt(KEY_ZOOM, 100));
        applyDesktopMode();
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String disposition,
                                        String mime, long length) {
                toast("تنزيل الملفات غير مدعوم");
            }
        });
    }

    private class Client extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return override(view, request.getUrl().toString(), request.isForMainFrame());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return override(view, url, true);   // أندرويد 6 فما دون
        }

        private boolean override(WebView view, String url, boolean mainFrame) {
            if (BrowserPages.isWebUrl(url) || url.startsWith("about:")) return false;
            if (!mainFrame) return !(url.startsWith("data:") || url.startsWith("blob:"));
            // قبل أندرويد 7 لا نعرف إن كان الطلب من إطار إعلاني، فلا نتبع الرابط البديل
            if (Build.VERSION.SDK_INT >= 24 && url.startsWith("intent:")) {
                try {
                    String fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url");
                    if (BrowserPages.isWebUrl(fallback)) {
                        view.loadUrl(fallback);
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            toast("هذا الرابط يحتاج تطبيقاً آخر ولا يعمل داخل المتصفح");
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()
                    && BrowserPages.HOME_HOST.equalsIgnoreCase(request.getUrl().getHost())) {
                byte[] html = BrowserPages.startPage(favorites()).getBytes(Charset.forName("UTF-8"));
                return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html));
            }
            return null;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            sslHandler.cancel();   // لا نتجاوز شهادة غير صالحة أبداً
            toast("اتصال غير آمن (شهادة غير صالحة): لم يُحمَّل المحتوى");
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageDialogs = 0;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            showAddress();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            showAddress();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            releaseWeb(true);
            toast("توقفت الصفحة وأُغلق المتصفح");
            finish();
            return true;
        }
    }

    private class Chrome extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int value) {
            progress.setProgress(value);
            progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            hideKeyboard();
            fullscreen.addView(view, match());
            fullscreen.setVisibility(View.VISIBLE);
            page.setVisibility(View.INVISIBLE);
            centerCursor();   // كي لا يبقى المؤشر فوق زر الخروج من ملء الشاشة
            showCursor();
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        // نوافذ الصفحات تحجز أزرار الريموت، فلا نسمح لها بأن تحبس المستخدم
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            if (!TextUtils.isEmpty(message)) {
                toast(message.length() > 200 ? message.substring(0, 200) + "…" : message);
            }
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            if (++pageDialogs <= MAX_PAGE_DIALOGS) return false;   // النافذة المعتادة
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String value,
                                  JsPromptResult result) {
            if (++pageDialogs <= MAX_PAGE_DIALOGS) return false;
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            result.confirm();   // المغادرة دائماً مسموحة
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        WebChromeClient.CustomViewCallback callback = customCallback;
        customView = null;
        customCallback = null;
        fullscreen.removeAllViews();
        fullscreen.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        if (web != null) web.requestFocus();
        showCursor();
        if (callback != null) callback.onCustomViewHidden();
    }

    private void load(String url) {
        if (web == null) return;
        web.requestFocus();
        web.loadUrl(url);
    }

    private static boolean isHome(String url) {
        return url == null || url.startsWith(BrowserPages.HOME_URL);
    }

    /** يعرض رابط الصفحة الحالية في شريط العنوان (ما لم يكن المستخدم يكتب فيه). */
    private void showAddress() {
        if (web == null) return;
        String url = web.getUrl();
        if (!urlBar.hasFocus()) {
            urlBar.setText(isHome(url) ? "" : url);
        }
        boolean saved = !isHome(url) && indexOfFavorite(favorites(), url) >= 0;
        starButton.setText(saved ? "★" : "☆");
        starButton.setTextColor(saved ? 0xFFFFD24A : Color.WHITE);
    }

    private void focusUrlBar() {
        if (customView != null) return;
        urlBar.requestFocus();
        urlBar.selectAll();
        urlBar.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && urlBar.hasFocus()) imm.showSoftInput(urlBar, 0);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- المفضلة والإعدادات

    private List<String[]> favorites() {
        List<String[]> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_FAVORITES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new String[]{o.getString("t"), o.getString("u")});
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private static int indexOfFavorite(List<String[]> list, String url) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[1].equals(url)) return i;
        }
        return -1;
    }

    private void toggleFavorite() {
        if (web == null) return;
        String url = web.getUrl();
        if (isHome(url) || !BrowserPages.isWebUrl(url)) {
            toast("افتح موقعاً أولاً ثم أضفه إلى المفضلة");
            return;
        }
        List<String[]> list = favorites();
        int at = indexOfFavorite(list, url);
        if (at >= 0) {
            list.remove(at);
            toast("حُذف من المفضلة");
        } else if (list.size() >= MAX_FAVORITES) {
            toast("المفضلة ممتلئة: احذف موقعاً أولاً");
            return;
        } else {
            String title = web.getTitle();
            list.add(new String[]{TextUtils.isEmpty(title) ? url : title, url});
            toast("أُضيف إلى المفضلة");
        }
        JSONArray a = new JSONArray();
        try {
            for (String[] f : list) {
                a.put(new JSONObject().put("t", f[0]).put("u", f[1]));
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_FAVORITES, a.toString()).apply();
        showAddress();
    }

    private void nextZoom() {
        if (web == null) return;
        int zoom = web.getSettings().getTextZoom() + 25;
        if (zoom > 175) zoom = 100;
        web.getSettings().setTextZoom(zoom);
        prefs.edit().putInt(KEY_ZOOM, zoom).apply();
        toast("حجم الخط " + zoom + "%");
    }

    private void toggleDesktop() {
        if (web == null) return;
        boolean desktop = !prefs.getBoolean(KEY_DESKTOP, false);
        prefs.edit().putBoolean(KEY_DESKTOP, desktop).apply();
        applyDesktopMode();
        web.reload();
        toast(desktop ? "نسخة الكمبيوتر" : "النسخة العادية");
    }

    private void applyDesktopMode() {
        boolean desktop = prefs.getBoolean(KEY_DESKTOP, false);
        WebSettings s = web.getSettings();
        if (desktop) {
            String version = "120.0.0.0";
            Matcher m = Pattern.compile("Chrome/([0-9.]+)")
                    .matcher(WebSettings.getDefaultUserAgent(this));
            if (m.find()) version = m.group(1);
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36");
        } else {
            s.setUserAgentString(null);
        }
        desktopButton.setTextColor(desktop ? ACCENT : Color.WHITE);
    }

    // ---------------------------------------------------------------- المؤشر

    private void centerCursor() {
        cursorX = root.getWidth() / 2f;
        cursorY = root.getHeight() / 2f;
        placeCursor();
    }

    private void placeCursor() {
        cursor.setTranslationX(cursorX);
        cursor.setTranslationY(cursorY);
    }

    private void showCursor() {
        cursor.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCursor);
        if (customView != null) handler.postDelayed(hideCursor, CURSOR_HIDE_MS);
    }

    /** أعلى منطقة المحتوى: أسفل الشريط، أو أعلى الشاشة في وضع ملء الشاشة. */
    private float contentTop() {
        return customView != null || web == null ? 0 : web.getTop();
    }

    private void moveCursor(int dx, int dy, int repeat) {
        typing = false;
        showCursor();
        float step = dp(Math.min(12 + repeat * 5, 60));   // يتسارع مع استمرار الضغط
        float margin = dp(8);
        float width = root.getWidth();
        float height = root.getHeight();
        float top = contentTop();
        boolean inContent = cursorY >= top;
        float x = cursorX + dx * step;
        float y = cursorY + dy * step;

        // الدفع نحو حافة المحتوى يمرّر الصفحة
        if (dy < 0 && inContent && y < top + margin) {
            // من أعلى المحتوى ننتقل إلى الشريط بضغطة جديدة بعد انتهاء التمرير
            if (customView != null || repeat > 0 || (web != null && web.canScrollVertically(-1))) {
                y = Math.min(cursorY, top + margin);
                if (!pressing) scroll(0, 1);
            }
        } else if (dy > 0 && y > height - margin) {
            y = height - margin;
            if (!pressing) scroll(0, -1);
        }
        if (dx < 0 && x < margin) {
            x = margin;
            if (inContent && !pressing) scroll(-1, 0);
        } else if (dx > 0 && x > width - margin) {
            x = width - margin;
            if (inContent && !pressing) scroll(1, 0);
        }
        cursorX = Math.max(0, x);
        cursorY = Math.max(0, y);
        placeCursor();
        if (pressing) touch(MotionEvent.ACTION_MOVE);   // سحب (شريط التقديم مثلاً)
    }

    /** يرسل حدث عجلة فأرة عند موضع المؤشر فيمرّر العنصر الذي تحته (حتى داخل الصفحة). */
    private void scroll(float horizontal, float vertical) {
        View target = customView != null ? fullscreen : web;
        if (target == null) return;
        float top = contentTop();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = cursorX;
        coords.y = Math.max(1, Math.min(cursorY - top, target.getHeight() - 1));
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        target.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    /** يحاكي لمسة عند طرف المؤشر. */
    private void touch(int action) {
        MotionEvent event = MotionEvent.obtain(pressTime, SystemClock.uptimeMillis(),
                action, cursorX, cursorY, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(event);
        event.recycle();
    }

    private boolean cursorOnUrlBar() {
        if (customView != null) return false;
        int[] bar = new int[2];
        int[] base = new int[2];
        urlBar.getLocationInWindow(bar);
        root.getLocationInWindow(base);
        float x = cursorX + base[0] - bar[0];
        float y = cursorY + base[1] - bar[1];
        return x >= 0 && y >= 0 && x < urlBar.getWidth() && y < urlBar.getHeight();
    }

    private void press() {
        if (pressing) return;
        typing = false;
        showCursor();
        pressing = true;
        pressTime = SystemClock.uptimeMillis();
        if (customView == null && web != null && cursorY >= contentTop()) web.requestFocus();
        touch(MotionEvent.ACTION_DOWN);
    }

    private void release(boolean cancelled) {
        if (!pressing) return;
        pressing = false;
        touch(cancelled ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP);
        if (!cancelled && cursorOnUrlBar()) focusUrlBar();
        showCursor();
    }

    /** مؤشر على شكل سهم، طرفه في الزاوية العليا اليسرى. لا يستقبل اللمس. */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(Context context) {
            super(context);
            fill.setColor(Color.WHITE);
            line.setColor(Color.BLACK);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(2 * context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            float u = Math.min(w, h) / 24f;
            arrow.reset();
            arrow.moveTo(u, u);
            arrow.lineTo(u, 19 * u);
            arrow.lineTo(6 * u, 15 * u);
            arrow.lineTo(9.5f * u, 22.5f * u);
            arrow.lineTo(13.5f * u, 20.8f * u);
            arrow.lineTo(10 * u, 13.5f * u);
            arrow.lineTo(16.5f * u, 13.5f * u);
            arrow.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, line);
        }
    }

    // ---------------------------------------------------------------- المفاتيح

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down) {
                if (e.getRepeatCount() == 0) backDown = true;
            } else if (backDown) {
                backDown = false;
                if (!e.isCanceled()) {
                    if (e.getEventTime() - e.getDownTime() >= LONG_BACK_MS) finish();
                    else handleBack();
                }
            }
            return true;
        }
        // مفاتيح لوحة المفاتيح على الشاشة تذهب إلى حقل الكتابة
        if ((e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return super.dispatchKeyEvent(e);
        if (down && (e.getUnicodeChar() >= ' ' || code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_FORWARD_DEL)) {
            typing = true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveCursor(-1, 0, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveCursor(1, 0, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveCursor(0, -1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveCursor(0, 1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Enter بعد الكتابة يبقى للحقل؛ وإلا فهو زر "موافق" في بعض أجهزة الريموت
                if (typing) return super.dispatchKeyEvent(e);
                // fall through
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) press();
                } else {
                    release(e.isCanceled());
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) scroll(0, 4);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) scroll(0, -4);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SEARCH:
                if (!down) focusUrlBar();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down) video("if(v.paused){v.play();}else{v.pause();}");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) video("v.play();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down) video("v.pause();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) video("v.currentTime-=10;");
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) video("v.currentTime+=10;");
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void video(String action) {
        if (web != null) web.evaluateJavascript(String.format(VIDEO_JS, action), null);
    }

    private void handleBack() {
        if (customView != null) {
            exitFullscreen();
        } else if (urlBar.hasFocus()) {
            web.requestFocus();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null) handleBack();
        else finish();
    }

    /** اللمس أو الفأرة الحقيقيان: لا حاجة للمؤشر. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        typing = true;
        if (cursor != null && !pressing) cursor.setVisibility(View.INVISIBLE);
        return super.dispatchTouchEvent(event);
    }

    // ---------------------------------------------------------------- دورة الحياة

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
        } else {
            release(true);
            backDown = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.onResume();
        handler.removeCallbacks(adhanWatch);
        handler.post(adhanWatch);
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            CookieManager.getInstance().flush();
            if (isFinishing()) releaseWeb(false);   // يوقف صوت الصفحة فوراً (قبل الأذان)
        }
        super.onPause();
    }

    /** لا يبقى المتصفح يعمل (ويصدر صوتاً) في الخلفية. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    /** crashed: عملية العرض انهارت، فلا يُستدعى أي شيء على الـ WebView غير الإتلاف. */
    private void releaseWeb(boolean crashed) {
        if (web == null) return;
        WebView dead = web;
        web = null;
        if (crashed) {
            customView = null;
            customCallback = null;
            fullscreen.removeAllViews();
        } else {
            exitFullscreen();
        }
        if (dead.getParent() instanceof ViewGroup) {
            ((ViewGroup) dead.getParent()).removeView(dead);
        }
        dead.destroy();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWeb(false);
        super.onDestroy();
    }
}
