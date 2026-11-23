package com.loai.azanhome;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * جسر بين الصفحة (JS) ومشغّل ExoPlayer. يُعرَّف في الصفحة باسم window.ExoBridge.
 * الفيديو يُرسم على SurfaceView خلف الـ WebView الشفاف.
 */
public class ExoBridge {

    private static final String UA = "Mozilla/5.0 (Linux; Android) AzanHome/1.0";

    /**
     * الشاشات القديمة (أندرويد 5 وما شابه) لا تحمل الشهادات الحديثة فيفشل الاتصال بمواقع البث.
     * true = لا يُتحقق من شهادة الخادم لروابط البث وقائمة القنوات فقط (لا تُستخدم لأي شيء آخر).
     * اجعلها false لإعادة التحقق الكامل.
     */
    private static final boolean RELAXED_TLS = true;

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static SSLSocketFactory relaxedSF() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }

    private final Activity act;
    private final SurfaceView surface;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView web;
    private ExoPlayer player;
    private int vw = 16, vh = 9;

    // حالة مقروءة من أي خيط
    private volatile int state = Player.STATE_IDLE;
    private volatile boolean playWhenReady = false;
    private volatile long pos = 0, dur = 0;
    private volatile String err = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                pos = player.getCurrentPosition();
                long d = player.getDuration();
                dur = (d == androidx.media3.common.C.TIME_UNSET || player.isCurrentMediaItemLive()) ? 0 : d;
                ui.postDelayed(this, 500);
            }
        }
    };

    public ExoBridge(Activity act, FrameLayout root, SurfaceView surface) {
        this.act = act;
        this.root = root;
        this.surface = surface;
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
    }

    private void fit() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        float ar = (float) vw / (float) vh;
        int w = W, h = Math.round(W / ar);
        if (h > H) { h = H; w = Math.round(H * ar); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    private void ensure() {
        if (player != null) return;
        DataSource.Factory http;
        SSLSocketFactory sf = RELAXED_TLS ? relaxedSF() : null;
        if (sf != null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(sf, TRUST_ALL)
                    .hostnameVerifier((h, ss) -> true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            http = new OkHttpDataSource.Factory(client).setUserAgent(UA);
        } else {
            http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .setUserAgent(UA);
        }
        player = new ExoPlayer.Builder(act,
                new DefaultRenderersFactory(act).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(20000, 40000, 2500, 5000)
                        .setTargetBufferBytes(16 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build())
                .build();
        player.setVideoSurfaceView(surface);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(1280, 720).build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { state = s; }
            @Override public void onPlayWhenReadyChanged(boolean p, int r) { playWhenReady = p; }
            @Override public void onVideoSizeChanged(VideoSize s) {
                if (s.width > 0 && s.height > 0) {
                    vw = Math.round(s.width * s.pixelWidthHeightRatio);
                    vh = s.height;
                    fit();
                }
            }
            @Override public void onPlayerError(PlaybackException e) {
                err = e.getErrorCodeName() + (e.getCause() != null ? " / " + e.getCause().getClass().getSimpleName() : "");
            }
        });
        ui.post(tick);
    }

    public void setWeb(WebView w) { this.web = w; }

    /** تحميل نص من الإنترنت من جهة الأندرويد؛ النتيجة تُرجَع إلى window.ipNative(id, code, text) */
    @JavascriptInterface
    public void fetch(final String url, final int id) {
        new Thread(() -> {
            int code = 0;
            String text = "";
            HttpURLConnection c = null;
            try {
                String u = url;
                for (int hop = 0; hop < 5; hop++) {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(40000);
                    c.setInstanceFollowRedirects(false);
                    if (RELAXED_TLS && c instanceof HttpsURLConnection) {
                        SSLSocketFactory f = relaxedSF();
                        if (f != null) {
                            ((HttpsURLConnection) c).setSSLSocketFactory(f);
                            ((HttpsURLConnection) c).setHostnameVerifier((h, ss) -> true);
                        }
                    }
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AzanHome/1.0");
                    code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                        c.disconnect();
                        continue;
                    }
                    break;
                }
                if (code == 200) {
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    text = bo.toString("UTF-8");
                }
            } catch (Exception e) {
                code = 0;
                text = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            final String js = "window.ipNative&&window.ipNative(" + id + "," + code + ","
                    + org.json.JSONObject.quote(text) + ");";
            ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
        }).start();
    }

    @JavascriptInterface
    public void play(final String url) {
        ui.post(() -> {
          try {
            ensure();
            err = "";
            pos = 0; dur = 0;
            surface.setVisibility(View.VISIBLE);
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.setPlayWhenReady(true);
          } catch (Throwable t) {
            err = "play: " + t.getClass().getSimpleName();
          }
        });
    }

    @JavascriptInterface public void resume() { ui.post(() -> { if (player != null) player.play(); }); }
    @JavascriptInterface public void pause()  { ui.post(() -> { if (player != null) player.pause(); }); }
    @JavascriptInterface public void seekTo(final double sec) {
        ui.post(() -> { if (player != null) player.seekTo((long) (sec * 1000)); });
    }

    @JavascriptInterface
    public void stop() {
        ui.post(() -> {
            if (player != null) { player.stop(); player.clearMediaItems(); }
            surface.setVisibility(View.GONE);
            state = Player.STATE_IDLE; playWhenReady = false; pos = 0; dur = 0;
        });
    }

    /** "state|playWhenReady|posSec|durSec|error" — state: 1 idle, 2 buffering, 3 ready, 4 ended */
    @JavascriptInterface
    public String state() {
        return state + "|" + (playWhenReady ? 1 : 0) + "|" + (pos / 1000.0) + "|" + (dur / 1000.0) + "|" + err;
    }

    public void release() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    public void pauseIfPlaying() { if (player != null) player.pause(); }
}
