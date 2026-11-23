package com.loai.azanhome;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * جسر بين الصفحة (JS) ومشغّل ExoPlayer. يُعرَّف في الصفحة باسم window.ExoBridge.
 * الفيديو يُرسم على SurfaceView خلف الـ WebView الشفاف.
 */
public class ExoBridge {

    private final Activity act;
    private final SurfaceView surface;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());

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
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setUserAgent("Mozilla/5.0 (Linux; Android) AzanHome/1.0");
        player = new ExoPlayer.Builder(act)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 50000, 1500, 3000).build())
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { state = s; }
            @Override public void onPlayWhenReadyChanged(boolean p, int r) { playWhenReady = p; }
            @Override public void onVideoSizeChanged(@NonNull VideoSize s) {
                if (s.width > 0 && s.height > 0) {
                    vw = Math.round(s.width * s.pixelWidthHeightRatio);
                    vh = s.height;
                    fit();
                }
            }
            @Override public void onPlayerError(@NonNull PlaybackException e) {
                err = e.getErrorCodeName() + (e.getCause() != null ? " / " + e.getCause().getClass().getSimpleName() : "");
            }
        });
        ui.post(tick);
    }

    @JavascriptInterface
    public void play(final String url) {
        ui.post(() -> {
            ensure();
            err = "";
            pos = 0; dur = 0;
            surface.setVisibility(View.VISIBLE);
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.setPlayWhenReady(true);
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
