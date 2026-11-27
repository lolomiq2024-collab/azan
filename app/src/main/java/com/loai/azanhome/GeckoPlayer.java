package com.loai.azanhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * مشغّل تجريبي للقوائم بمحرك Firefox (GeckoView 143، آخر إصدار يدعم أندرويد 5)
 * المضمّن في التطبيق، بدل WebView القديم الذي لا يمكن تحديثه على هذه الشاشات.
 */
final class GeckoPlayer implements MoviePlayer {

    interface Events {
        /** بدأ الفيديو فعلاً: المحرك يعمل على هذا الجهاز. */
        void playing();

        /** انهارت عملية العرض أو أوقفها النظام (غالباً لقلة الذاكرة). */
        void failed();

        /** مشكلة في فتح صفحة المشغّل، لعرضها على المستخدم. */
        void problem(String detail);
    }

    /** مهلة فتح صفحة المشغّل قبل إبلاغ المستخدم. */
    private static final long PAGE_WAIT_MS = 25000;

    /** مهلة تأكيد الصفحة أنها توقفت للأذان، وإلا تُغلق الصفحة حتى لا يُسمع صوتها. */
    private static final long HOLD_ACK_MS = 3000;
    private static final long EPISODES_WAIT_MS = 5000;

    private static final String CONFIG =
            "prefs:\n"
            // التشغيل التلقائي بالصوت
            + "  media.autoplay.default: 0\n"
            // نطلب من يوتيوب صيغة H.264 لأن شاشات التلفاز تفك ترميزها بالعتاد، بخلاف VP9 وAV1
            + "  media.mediasource.webm.enabled: false\n"
            + "  media.mediasource.vp9.enabled: false\n"
            + "  media.av1.enabled: false\n";

    /** محرك واحد فقط لكل تشغيل للتطبيق. */
    private static GeckoRuntime runtime;

    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final GeckoRuntime engine;
    private final PlayerServer server;
    private final GeckoView view;
    private GeckoSession session;
    private String id;
    private String type;
    private boolean held = false;
    private boolean holdConfirmed = false;
    private boolean reload = false;     // أُغلقت الصفحة أثناء الأذان وتُحمَّل من جديد بعده
    private boolean destroyed = false;
    private Episodes waiting;
    private boolean alive = false;      // وصلت أول رسالة من الصفحة

    private final Runnable checkHold = this::checkHold;
    private final Runnable noEpisodes = () -> answerEpisodes(0, 0);

    private final Runnable checkAlive = this::checkAlive;

    /** نسخة التطبيق تحوي المحرك لمعالجات ARM 32-bit فقط. */
    static boolean supported() {
        return Arrays.asList(Build.SUPPORTED_ABIS).contains("armeabi-v7a");
    }

    GeckoPlayer(Activity activity, Events events) throws IOException {
        this.events = events;
        engine = runtime(activity.getApplicationContext());
        server = new PlayerServer(new PlayerServer.Listener() {
            @Override
            public void onState(int gen, String state) {
                ui.post(() -> {
                    if (destroyed || gen != server.generation()) return;
                    if ("alive".equals(state)) {
                        alive = true;
                        ui.removeCallbacks(checkAlive);
                    } else if ("held".equals(state)) holdConfirmed = true;
                    else if ("playing".equals(state)) GeckoPlayer.this.events.playing();
                });
            }

            @Override
            public void onEpisodes(int gen, int count, int index) {
                ui.post(() -> {
                    if (!destroyed && gen == server.generation()) answerEpisodes(count, index);
                });
            }
        });
        view = new GeckoView(activity);
        view.coverUntilFirstPaint(Color.BLACK);     // الافتراضي أبيض
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
    }

    private static GeckoRuntime runtime(Context context) throws IOException {
        if (runtime == null) {
            File config = new File(context.getFilesDir(), "gecko-config.yaml");
            try (FileOutputStream out = new FileOutputStream(config)) {
                out.write(CONFIG.getBytes("UTF-8"));
            }
            runtime = GeckoRuntime.create(context, new GeckoRuntimeSettings.Builder()
                    .configFilePath(config.getAbsolutePath())
                    .aboutConfigEnabled(false)
                    .build());
        }
        return runtime;
    }

    @Override
    public View view() {
        return view;
    }

    private void openSession() {
        if (session != null) return;
        GeckoSession s = new GeckoSession();
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession dead) {
                lost(dead);
            }

            @Override
            public void onKill(GeckoSession dead) {
                lost(dead);
            }
        });
        // لا نسمح بالخروج من صفحة المشغّل
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession from, LoadRequest request) {
                boolean ours = request.uri.startsWith(server.base()) || "about:blank".equals(request.uri);
                return GeckoResult.fromValue(ours ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession from, String uri, WebRequestError error) {
                if (!destroyed && from == session) {
                    events.problem("خطأ فتح الصفحة " + error.category + "/" + error.code);
                }
                return null;
            }
        });
        // التشغيل التلقائي فقط، وما عداه (الكاميرا، الموقع...) مرفوض
        s.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession from, ContentPermission p) {
                boolean autoplay = p.permission == PERMISSION_AUTOPLAY_AUDIBLE
                        || p.permission == PERMISSION_AUTOPLAY_INAUDIBLE;
                return GeckoResult.fromValue(autoplay
                        ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
            }
        });
        s.open(engine);
        view.setSession(s);
        session = s;
    }

    private void closeSession() {
        if (session == null) return;
        GeckoSession s = session;
        session = null;
        view.releaseSession();
        s.close();
    }

    private void lost(GeckoSession dead) {
        if (destroyed || dead != session) return;
        closeSession();
        if (held) reload = true;    // نعيد التحميل بعد الأذان
        else events.failed();
    }

    @Override
    public void load(String newId, String newType) {
        if (destroyed) return;
        id = newId;
        type = newType;
        reload = false;
        openSession();
        session.setActive(true);
        alive = false;
        session.loadUri(server.load(gen -> PlayerPage.html(newId, newType, server.origin(),
                PlayerPage.remoteScript(gen))));
        ui.removeCallbacks(checkAlive);
        ui.postDelayed(checkAlive, PAGE_WAIT_MS);
    }

    private void checkAlive() {
        if (destroyed || alive || held || session == null) return;
        events.problem("محرك Firefox لم يفتح صفحة المشغّل");
    }

    @Override
    public void command(String command) {
        if (!destroyed && session != null) server.send(command);
    }

    @Override
    public void episodes(Episodes callback) {
        if (destroyed || session == null) {
            callback.result(0, 0);
            return;
        }
        waiting = callback;
        server.send("episodes");
        ui.removeCallbacks(noEpisodes);
        ui.postDelayed(noEpisodes, EPISODES_WAIT_MS);
    }

    private void answerEpisodes(int count, int index) {
        ui.removeCallbacks(noEpisodes);
        Episodes callback = waiting;
        waiting = null;
        if (callback != null) callback.result(count, index);
    }

    private void forgetEpisodes() {
        ui.removeCallbacks(noEpisodes);
        waiting = null;
    }

    @Override
    public void hold() {
        if (destroyed || held) return;
        held = true;
        holdConfirmed = false;
        forgetEpisodes();
        if (session == null) return;
        server.send("hold");
        ui.postDelayed(checkHold, HOLD_ACK_MS);
    }

    private void checkHold() {
        if (destroyed || !held || session == null) return;
        if (holdConfirmed) {
            session.setActive(false);
        } else {    // إغلاق الصفحة هو الضمان الوحيد لإسكاتها
            reload = true;
            server.load(null);
            closeSession();
        }
    }

    @Override
    public void release() {
        if (destroyed || !held) return;
        held = false;
        ui.removeCallbacks(checkHold);
        if (reload && id != null) {
            load(id, type);
        } else if (session != null) {
            session.setActive(true);
            server.send("release");
        }
    }

    @Override
    public void onPause() {
        if (!held) command("pause");
    }

    @Override
    public void onResume() {
        if (!destroyed && !held && session != null) session.setActive(true);
    }

    @Override
    public void stop() {
        if (destroyed) return;
        ui.removeCallbacks(checkHold);
        ui.removeCallbacks(checkAlive);
        forgetEpisodes();
        id = null;
        reload = false;
        server.load(null);
        closeSession();
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        server.close();
    }
}
