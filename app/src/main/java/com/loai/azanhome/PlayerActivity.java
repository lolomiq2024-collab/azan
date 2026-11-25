package com.loai.azanhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * مشغّل البث المباشر. يعمل في عملية مستقلة (android:process=":player")
 * فإذا انهار فك ترميز إحدى القنوات تُغلق هذه الشاشة وحدها ويبقى اللانشر يعمل.
 * الرابط الذي سبّب انهياراً يُؤخَّر في المرة التالية ويُجرَّب بفك ترميز برمجي.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_URLS = "urls";
    /** نتيجة: فشل الاتصال الآمن، فلتجرّب الصفحة مشغّل الويب. */
    static final int RESULT_USE_WEB = RESULT_FIRST_USER;
    /** ملف يبقى موجوداً إن انهارت عملية المشغّل أثناء التشغيل، ومحتواه الرابط. */
    static final String CRASH_MARKER = "playing.url";

    private static final String TAG = "AzanPlayer";
    private static final String PREFS = "player";
    private static final long STALL_MS = 15000;
    /** مهلة قبل بدء التشغيل حتى تستقر الشاشة الرئيسية في الخلفية. */
    private static final long START_DELAY_MS = 1200;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> urls = new ArrayList<>();
    private final Set<String> crashed = new HashSet<>();

    private FrameLayout root;
    private SurfaceView surface;
    private TextView status;
    private ExoPlayer player;
    private SharedPreferences prefs;
    private String title = "";
    private int index = 0;
    private boolean asHls = false;
    private boolean dead = false;
    private boolean sslFailed = false;
    /** وضع متساهل مع شهادات HTTPS (للأجهزة القديمة التي تنقصها شهادات الجذر). يُفعَّل عند أول خطأ SSL ويُلغى عند إغلاق المشغّل. */
    private boolean lenient = false;
    private SSLSocketFactory prevFactory;
    private HostnameVerifier prevVerifier;

    private final Runnable stall = this::next;
    private final Runnable advance = this::advance;
    private final Runnable start = () -> open(0);
    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // أعاد النظام إنشاء الشاشة بعد موت العملية: لا نعيد تشغيل ما أسقطها
        if (savedInstanceState != null) {
            finish();
            return;
        }
        // خطأ غير متوقع: أغلق عملية المشغّل بهدوء دون نافذة "توقف التطبيق"
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.e(TAG, "player crashed", error);
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        status.setPadding(40, 20, 40, 20);
        status.setBackgroundColor(0xAA000000);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = 60;
        root.addView(status, sp);
        root.setOnClickListener(v -> toggle());
        setContentView(root);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        loadUrls(getIntent().getStringExtra(EXTRA_URLS));
        if (urls.isEmpty()) {
            fail("لا يوجد رابط لهذه القناة");
            return;
        }
        show(title + "\nجارٍ التحميل…", false);
    }

    /** يرتّب الروابط: آخر رابط ناجح أولاً، والروابط التي سبّبت انهياراً في الآخر. */
    private void loadUrls(String json) {
        // علامة متبقية من تشغيل سابق تعني أن ذلك الرابط أسقط المشغّل
        crashed.addAll(prefs.getStringSet("crashed", new HashSet<String>()));
        String last = CrashMarker.take(marker());
        if (last != null) {
            crashed.add(last);
            prefs.edit().putStringSet("crashed", new HashSet<>(crashed)).commit();
        }
        List<String> bad = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                String u = a.getString(i);
                if (crashed.contains(u)) bad.add(u); else urls.add(u);
            }
        } catch (Exception e) {
            Log.w(TAG, "bad url list", e);
        }
        String ok = prefs.getString("ok_" + title, null);
        if (ok != null && urls.remove(ok)) urls.add(0, ok);
        urls.addAll(bad);
    }

    private File marker() {
        return new File(getFilesDir(), CRASH_MARKER);
    }

    private void open(int i) {
        releasePlayer();
        index = i;
        asHls = false;
        String url = urls.get(i);
        show(title + "\nجارٍ التحميل…"
                + (urls.size() > 1 ? " (الرابط " + (i + 1) + " من " + urls.size() + ")" : ""), false);
        try {
            CrashMarker.write(marker(), url);
            player = build(crashed.contains(url));
            player.setVideoSurfaceView(surface);
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.play();
            armStall();
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
            next();
        }
    }

    private ExoPlayer build(boolean preferSoftware) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setUserAgent("Mozilla/5.0 (Linux; Android) AzanHome/1.0");
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        if (preferSoftware) {
            renderers.setMediaCodecSelector((mime, secure, tunneling) -> {
                List<MediaCodecInfo> list = new ArrayList<>(
                        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling));
                // البرمجي أولاً
                Collections.sort(list, (a, b) ->
                        Boolean.compare(a.hardwareAccelerated, b.hardwareAccelerated));
                return list;
            });
        }
        ExoPlayer p = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(20000, 40000, 2500, 5000)
                        .setTargetBufferBytes(16 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build())
                .build();
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(1280, 720).build());
        p.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (p != player) return;
                if (state == Player.STATE_READY) {
                    ui.removeCallbacks(stall);
                    dead = false;
                    prefs.edit().putString("ok_" + title, urls.get(index)).apply();
                    show(title, true);
                } else if (state == Player.STATE_BUFFERING) {
                    show(title + "\nجارٍ التحميل…", false);
                    armStall();
                } else if (state == Player.STATE_ENDED) {
                    next();   // البث المباشر لا ينتهي
                }
            }

            @Override public void onVideoSizeChanged(VideoSize s) {
                if (p == player && s.width > 0 && s.height > 0) {
                    fit(s.width * s.pixelWidthHeightRatio / s.height);
                }
            }

            @Override public void onPlayerError(PlaybackException e) {
                if (p != player) return;
                Log.w(TAG, "playback error " + e.getErrorCodeName(), e);
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition();
                    p.prepare();
                    return;
                }
                // رابط HLS بلا امتداد .m3u8
                if (!asHls && e.errorCode
                        == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true;
                    p.setMediaItem(new MediaItem.Builder()
                            .setUri(Uri.parse(urls.get(index)))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build());
                    p.prepare();
                    return;
                }
                for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                    if (c instanceof SSLException) sslFailed = true;
                }
                if (sslFailed && !lenient) {
                    enableLenientSsl();
                    if (lenient) {
                        sslFailed = false;
                        final int again = index;
                        ui.post(() -> { if (!isFinishing() && !dead) open(again); });
                        return;
                    }
                }
                next();
            }
        });
        return p;
    }

    private void enableLenientSsl() {
        if (lenient) return;
        try {
            prevFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
            prevVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
            lenient = true;
        } catch (Throwable t) {
            Log.w(TAG, "lenient ssl failed", t);
        }
    }

    private void restoreSsl() {
        if (!lenient) return;
        try {
            if (prevFactory != null) HttpsURLConnection.setDefaultSSLSocketFactory(prevFactory);
            if (prevVerifier != null) HttpsURLConnection.setDefaultHostnameVerifier(prevVerifier);
        } catch (Throwable ignored) { }
        lenient = false;
    }

    @Override
    protected void onDestroy() {
        restoreSsl();
        super.onDestroy();
    }

    /** مهلة التعليق: لا تُحتسب أثناء الإيقاف المؤقت. */
    private void armStall() {
        ui.removeCallbacks(stall);
        if (player != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_READY) {
            ui.postDelayed(stall, STALL_MS);
        }
    }

    /** يطلب الانتقال للرابط التالي (خارج استدعاءات المشغّل، ومرة واحدة فقط). */
    private void next() {
        ui.removeCallbacks(stall);
        ui.removeCallbacks(advance);
        ui.post(advance);
    }

    /** ينتقل للرابط التالي، أو يعلن الفشل بعد تجربة الجميع. */
    private void advance() {
        if (isFinishing()) return;
        if (index + 1 < urls.size()) {
            open(index + 1);
        } else if (sslFailed && !lenient) {
            setResult(RESULT_USE_WEB);
            finish();
        } else {
            fail("تعذّر تشغيل القناة\nاضغط موافق لإعادة المحاولة، أو رجوع للخروج");
        }
    }

    private void fail(String message) {
        dead = true;
        releasePlayer();
        show(title + "\n" + message, false);
    }

    private void show(String text, boolean autoHide) {
        ui.removeCallbacks(hideStatus);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        if (autoHide) ui.postDelayed(hideStatus, 3000);
    }

    private void fit(float ratio) {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0 || ratio <= 0) return;
        int vw = w, vh = Math.round(w / ratio);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * ratio);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private void toggle() {
        if (dead) {
            if (!urls.isEmpty()) {
                dead = false;
                sslFailed = false;
                open(0);
            }
        } else if (player != null) {
            if (player.getPlayWhenReady()) {
                ui.removeCallbacks(stall);
                player.pause();
                show(title + "\nمتوقف مؤقتاً ❚❚", false);
            } else {
                player.seekToDefaultPosition();   // العودة إلى البث الحي
                player.play();
                show(title, true);
                armStall();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_SPACE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) toggle();
            return true;
        }
        return super.dispatchKeyEvent(e);   // زر الرجوع يغلق المشغّل
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (player != null) {
            CrashMarker.write(marker(), urls.get(index));
        } else if (!dead && !urls.isEmpty()) {
            ui.removeCallbacks(start);
            ui.postDelayed(start, START_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        // خروج طبيعي من الشاشة: احذف علامة الانهيار
        if (root != null) CrashMarker.clear(marker());
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (root != null) {
            ui.removeCallbacksAndMessages(null);
            releasePlayer();
            finish();   // لا تشغيل في الخلفية
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(stall);
        if (player != null) {
            ExoPlayer p = player;
            player = null;
            p.release();
        }
    }
}
