package com.loai.azanhome;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * مشغّل قائمة أصلي (بلا WebView): يستخرج رابط كل حلقة عبر YoutubeSource ويشغّله بـ ExoPlayer.
 * - يجهّز الحلقة التالية مسبقاً (prefetch) ويجدّد الرابط إن انتهت صلاحيته.
 * - إن فشلت حلقة يعيد المحاولة بتجديد الرابط مرة، ثم ينتقل للتالية، ويتوقف بعد 5 إخفاقات متتالية.
 * - القائمة تُعاد من أولها عند انتهائها، والفيديو المفرد يُعاد عند انتهائه.
 * كل الدوال العامة تُستدعى من خيط الواجهة.
 */
final class NativeQueuePlayer {

    private static final int MAX_FAILURES = 5;
    private static final long SEEK_MS = 10_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, YoutubeSource.Stream> cache =
            Collections.synchronizedMap(new HashMap<String, YoutubeSource.Stream>());
    private final List<YoutubeSource.Episode> items = new ArrayList<>();

    private final FrameLayout view;
    private final SurfaceView surface;
    private final TextView status;

    private ExoPlayer player;
    private int index = -1;
    private int gen = 0;          // يُبطل نتائج الطلبات القديمة
    private int retried = 0;
    private int failures = 0;
    private boolean single = false;
    private boolean held = false;        // الأذان
    private boolean wasPlaying = false;  // كان يعمل قبل الأذان
    private boolean lifePaused = false;  // التطبيق في الخلفية
    private boolean destroyed = false;
    private int videoW = 0;
    private int videoH = 0;
    private float pixelRatio = 1f;

    NativeQueuePlayer(Activity activity) {
        this.activity = activity;

        view = new FrameLayout(activity);
        view.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(activity);
        view.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        status = new TextView(activity);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        status.setPadding(40, 20, 40, 20);
        status.setBackgroundColor(0xAA000000);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        view.addView(status, sp);

        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
    }

    View view() {
        return view;
    }

    // ---------- التشغيل ----------

    /** يبدأ قائمة تشغيل (type = p) أو فيديواً مفرداً (type = v). */
    void start(final String id, String type) {
        if (destroyed) return;
        stopPlayback();
        single = "v".equals(type);
        items.clear();
        index = -1;
        failures = 0;
        final int g = ++gen;
        show("جارٍ تحميل القائمة…");
        io.execute(() -> {
            try {
                final List<YoutubeSource.Episode> list = new ArrayList<>();
                if (single) {
                    list.add(new YoutubeSource.Episode(YoutubeSource.watchUrl(id), ""));
                } else {
                    list.addAll(YoutubeSource.playlist(activity, id));
                }
                ui.post(() -> {
                    if (g != gen || destroyed) return;
                    items.addAll(list);
                    playAt(0);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (g == gen && !destroyed) show("تعذر تحميل القائمة\n" + describe(e));
                });
            }
        });
    }

    void playAt(int i) {
        if (destroyed || i < 0 || i >= items.size()) return;
        index = i;
        retried = 0;
        load(false);
    }

    void next() {
        if (items.isEmpty()) return;
        playAt((index + 1) % items.size());
    }

    void previous() {
        if (items.isEmpty()) return;
        playAt(index > 0 ? index - 1 : items.size() - 1);
    }

    private void load(final boolean fresh) {
        final int g = ++gen;
        final int i = index;
        final YoutubeSource.Episode ep = items.get(i);
        show(label(i) + "\nجارٍ التحميل…");
        io.execute(() -> {
            try {
                final YoutubeSource.Stream s = streamFor(ep, fresh);
                ui.post(() -> {
                    if (g == gen && !destroyed) startStream(s, i);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (g == gen && !destroyed) fail(describe(e));
                });
            }
        });
    }

    private YoutubeSource.Stream streamFor(YoutubeSource.Episode ep, boolean fresh) throws Exception {
        if (!fresh) {
            YoutubeSource.Stream cached = cache.get(ep.url);
            if (cached != null && cached.expiresAt - 30_000 > System.currentTimeMillis()) return cached;
        }
        YoutubeSource.Stream s = YoutubeSource.resolve(activity, ep.url);
        cache.put(ep.url, s);
        return s;
    }

    private void startStream(YoutubeSource.Stream s, int i) {
        ensurePlayer();
        player.setMediaItem(MediaItem.fromUri(Uri.parse(s.url)));
        player.prepare();
        if (held) {
            wasPlaying = true;       // يبدأ بعد انتهاء الأذان
            player.setPlayWhenReady(false);
        } else {
            player.setPlayWhenReady(!lifePaused);
        }
        prefetch(i + 1);
    }

    private void prefetch(int i) {
        if (single || i >= items.size()) return;
        final YoutubeSource.Episode ep = items.get(i);
        io.execute(() -> {
            try {
                streamFor(ep, false);
            } catch (Exception ignored) {
                // فشل التجهيز المسبق لا يوقف التشغيل الحالي
            }
        });
    }

    private void fail(String why) {
        if (items.isEmpty() || index < 0) return;
        if (retried < 1) {
            retried++;
            cache.remove(items.get(index).url);
            load(true);          // نجدّد الرابط ونعيد المحاولة
            return;
        }
        failures++;
        if (failures >= MAX_FAILURES || (single && items.size() < 2)) {
            show("تعذر التشغيل\n" + why);
            return;
        }
        show(label(index) + "\nتعذر التشغيل، ننتقل للتالية…\n" + why);
        final int g = gen;
        ui.postDelayed(() -> {
            if (g == gen && !destroyed) next();
        }, 1500);
    }

    private void onEnded() {
        if (single) {
            if (player != null) {
                player.seekTo(0);
                player.setPlayWhenReady(!held);
            }
        } else {
            next();
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000);
        player = new ExoPlayer.Builder(activity)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    failures = 0;
                    hide();
                } else if (state == Player.STATE_BUFFERING) {
                    show("جارٍ التحميل…");
                } else if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                fail(error.getErrorCodeName());
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                pixelRatio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
                fit();
            }
        });
    }

    /** يلائم الفيديو أبعاد الشاشة كاملاً دون قص أو تمطيط (قد تظهر حواف سوداء). */
    private void fit() {
        int w0 = view.getWidth();
        int h0 = view.getHeight();
        if (w0 <= 0 || h0 <= 0 || videoW <= 0 || videoH <= 0) return;
        float ratio = videoW * pixelRatio / videoH;
        int w = w0;
        int h = Math.round(w0 / ratio);
        if (h > h0) {
            h = h0;
            w = Math.round(h0 * ratio);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    // ---------- التحكم ----------

    void togglePlay() {
        if (player == null || held) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    void play() {
        if (player != null && !held) player.setPlayWhenReady(true);
    }

    void pause() {
        if (player != null && !held) player.setPlayWhenReady(false);
    }

    void seekBy(int direction) {
        if (player == null || held) return;
        long pos = Math.max(0, player.getCurrentPosition() + direction * SEEK_MS);
        long dur = player.getDuration();
        if (dur > 0) pos = Math.min(pos, Math.max(0, dur - 1000));
        player.seekTo(pos);
    }

    int count() {
        return items.size();
    }

    int currentIndex() {
        return index;
    }

    String[] titles() {
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) {
            String t = items.get(i).title;
            names[i] = "الحلقة " + (i + 1) + (t.isEmpty() ? "" : " — " + t);
        }
        return names;
    }

    // ---------- الأذان ودورة الحياة ----------

    /** بداية الأذان: يتوقف الفيديو في مكانه ويُكمل بعد انتهائه إن كان يعمل. */
    void hold() {
        if (held || destroyed) return;
        held = true;
        wasPlaying = player == null || player.getPlayWhenReady();
        if (player != null) player.setPlayWhenReady(false);
    }

    void release() {
        if (!held || destroyed) return;
        held = false;
        if (player != null && wasPlaying && !lifePaused) player.setPlayWhenReady(true);
        wasPlaying = false;
    }

    void onPause() {
        if (destroyed || lifePaused) return;
        lifePaused = true;
        if (!held) {
            wasPlaying = player != null && player.getPlayWhenReady();
            if (player != null) player.setPlayWhenReady(false);
        }
    }

    void onResume() {
        if (destroyed || !lifePaused) return;
        lifePaused = false;
        if (!held && player != null && wasPlaying) player.setPlayWhenReady(true);
        if (!held) wasPlaying = false;
    }

    /** يوقف التشغيل ويفرغ القائمة (عند الرجوع لقائمة القوائم). */
    void stop() {
        gen++;
        stopPlayback();
        items.clear();
        index = -1;
        hide();
    }

    private void stopPlayback() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        gen++;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---------- مساعدات ----------

    private String label(int i) {
        String t = items.get(i).title;
        String n = items.size() > 1 ? "الحلقة " + (i + 1) + " / " + items.size() : "";
        return t.isEmpty() ? n : (n.isEmpty() ? t : n + " — " + t);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void show(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hide() {
        status.setVisibility(View.GONE);
    }
}
