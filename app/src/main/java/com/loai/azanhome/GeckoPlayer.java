package com.loai.azanhome;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

        /** مراحل التشغيل، تُعرض فوق المشغّل إلى أن يبدأ الفيديو. */
        void status(String text);
    }

    /** مهلة تأكيد الصفحة أنها توقفت للأذان، وإلا تُغلق الصفحة حتى لا يُسمع صوتها. */
    private static final long HOLD_ACK_MS = 3000;
    private static final long EPISODES_WAIT_MS = 5000;
    private static final String TAG = "AzanGecko";

    // المراحل بالترتيب: {المعرّف, الاسم}
    private static final String[][] STAGES = {
            {"page", "المحرك طلب صفحة المشغّل"},
            {"drawn", "الرسم على الشاشة"},
            {"script", "سكربت الصفحة يعمل"},
            {"apijs", "وصل ملف مشغّل يوتيوب"},
            {"api", "بدأ تجهيز المشغّل"},
            {"ready", "مشغّل يوتيوب جاهز"},
            {"playing", "بدأ الفيديو"},
    };

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
    private final Set<String> reached = new HashSet<>();
    private String problem;
    private final Map<String, String> network = new LinkedHashMap<>();

    private final Runnable checkHold = this::checkHold;
    private final Runnable noEpisodes = () -> answerEpisodes(0, 0);

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
                    if ("held".equals(state)) {
                        holdConfirmed = true;
                    } else if ("playing".equals(state)) {
                        Log.i(TAG, "stage playing");
                        GeckoPlayer.this.events.playing();
                    } else if (state.startsWith("error")) {
                        trouble("خطأ يوتيوب رقم " + state.substring(5));
                    } else if ("noapi".equals(state)) {
                        trouble("تعذّر الاتصال بيوتيوب");
                    } else if (state.startsWith("net-")) {
                        probed(state);
                    } else {
                        stage(state);
                    }
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

    private void stage(String name) {
        Log.i(TAG, "stage " + name);
        reached.add(name);
        showStatus();
    }

    private void trouble(String text) {
        Log.w(TAG, text);
        problem = text;
        showStatus();
    }

    /** state: net-الموقع-النتيجة */
    private void probed(String state) {
        Log.i(TAG, state);
        String[] p = state.split("-");
        if (p.length != 3) return;
        network.put(p[1], "ok".equals(p[2]) ? "✓" : "slow".equals(p[2]) ? "لا رد" : "✗");
        showStatus();
    }

    private void showStatus() {
        if (destroyed || reached.contains("playing")) return;
        StringBuilder b = new StringBuilder("مشغّل Firefox (إن لم يعمل صوّر هذه القائمة)");
        for (String[] s : STAGES) {
            if (!"playing".equals(s[0])) {
                b.append('\n').append(reached.contains(s[0]) ? "✓ " : "… ").append(s[1]);
            }
        }
        if (!network.isEmpty()) {
            b.append("\nالإنترنت:");
            for (Map.Entry<String, String> n : network.entrySet()) {
                b.append("  ").append(n.getKey()).append(' ').append(n.getValue());
            }
        }
        if (problem != null) b.append("\n✗ ").append(problem);
        events.status(b.toString());
    }

    private void openSession() {
        if (session != null) return;
        GeckoSession s = new GeckoSession();
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFirstComposite(GeckoSession from) {
                if (from == session) stage("drawn");
            }

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
                if (!ours) Log.w(TAG, "blocked " + request.uri);
                return GeckoResult.fromValue(ours ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession from, String uri, WebRequestError error) {
                if (from == session) {
                    trouble("خطأ المحرك في فتح الصفحة: " + error.category + "/" + error.code);
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
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession from, String url) {
                Log.i(TAG, "page start " + url.replaceFirst("/[0-9a-f]{32}/", "/*/"));
            }

            @Override
            public void onPageStop(GeckoSession from, boolean success) {
                Log.i(TAG, "page stop " + success);
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
        Log.w(TAG, "content process lost");
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
        reached.clear();
        problem = null;
        network.clear();
        showStatus();
        openSession();
        session.setActive(true);
        session.loadUri(server.load(gen -> PlayerPage.html(newId, newType, server.origin(),
                PlayerPage.remoteScript(gen))));
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
