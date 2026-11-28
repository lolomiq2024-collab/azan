package com.loai.azanhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * أفلام ومسلسلات: صفحة assets/movies.html تُعرض فوق الصفحة الرئيسية بملء الشاشة داخل MainActivity.
 * التطبيق يحمّل القوائم ويمرّرها للصفحة، والصفحة تعرضها وتشغّلها.
 * عند الأذان تُخفى اللوحة ويتوقف الفيديو (hold)، وبعد انتهائه تعود ويكمل من مكانه (release).
 */
final class MoviesPanel {

    // رابط ملف القوائم على GitHub: عدّل الملف هناك فتتحدث القوائم تلقائياً دون إعادة بناء التطبيق
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/lolomiq2024-collab/yt-playlists/main/playlists.json";

    // احتياط أخير إن تعذّرت قراءة الملف المرفق assets/playlists.json: {الاسم, المعرّف, النوع}
    // النوع: p = قائمة تشغيل ، v = فيديو مفرد
    private static final String[][] DEFAULTS = {
            {"مايا النحلة", "PL6qk-0ihmmB4O53zIbVntvbJIy_qgtbJk", "p"}
    };

    // هوية التضمين: معرّف حزمة التطبيق (يطلبها يوتيوب لتجنّب الخطأ 152/153)
    private static final String ORIGIN = "https://com.loai.azanhome";

    private static final String PREFS = "yt_playlists";
    private static final String KEY_USER = "user_lists";
    private static final String KEY_REMOTE = "remote_json";
    private static final String KEY_LAST_ID = "last_id";

    private static final long REFRESH_INTERVAL_MS = 10 * 60 * 1000L;

    private static final int BG = 0xFF0F1720;

    private static final Pattern LIST_PARAM = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern VIDEO_URL =
            Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern LIST_ID = Pattern.compile("^(PL|UU|OL|FL|LL)[A-Za-z0-9_-]{10,}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{10,}$");

    private final ArrayList<String[]> remote = new ArrayList<>();
    private final ArrayList<String[]> user = new ArrayList<>();
    private final ArrayList<String[]> lists = new ArrayList<>();
    private String currentId = null;
    private boolean playing = false;
    private long lastFetch = 0;

    private final Activity activity;
    private final Runnable onClose;
    private boolean held = false;       // الأذان يعمل الآن
    private boolean destroyed = false;
    private AlertDialog dialog;
    private boolean pageReady = false;

    private final FrameLayout rootFrame;
    private WebView web;

    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    MoviesPanel(Activity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;

        loadLists();
        setupWebView();

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(BG);
        rootFrame.setClickable(true);   // لا تمرّ اللمسات إلى الصفحة التي تحتها
        rootFrame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String page = readAsset("movies.html");
        if (page != null) {
            web.loadDataWithBaseURL(ORIGIN, page, "text/html", "utf-8", null);
        }

        fetchRemote();
    }

    View view() {
        return rootFrame;
    }

    // ---------- بيانات القوائم ----------

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadLists() {
        SharedPreferences sp = prefs();

        remote.clear();
        String cached = sp.getString(KEY_REMOTE, null);
        if (cached == null || !parseRemote(cached, remote)) {
            remote.clear();
            // قبل أول تحميل ناجح: النسخة المرفقة بالتطبيق من ملف القوائم
            String bundled = readAsset("playlists.json");
            if (bundled == null || !parseRemote(bundled, remote)) {
                for (String[] d : DEFAULTS) remote.add(d);
            }
        }

        user.clear();
        String raw = sp.getString(KEY_USER, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    user.add(new String[]{o.getString("name"), o.getString("id"), o.optString("type", "p")});
                }
            } catch (Exception ignored) {
            }
        }
        rebuild();
    }

    private String readAsset(String name) {
        try (InputStream in = activity.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void saveUser() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] l : user) {
                JSONObject o = new JSONObject();
                o.put("name", l[0]);
                o.put("id", l[1]);
                o.put("type", l[2]);
                arr.put(o);
            }
            prefs().edit().putString(KEY_USER, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void rebuild() {
        lists.clear();
        lists.addAll(remote);
        lists.addAll(user);
    }

    /** يرسل القوائم إلى الصفحة. */
    private void sendLists() {
        if (destroyed || !pageReady) return;
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < lists.size(); i++) {
                String[] l = lists.get(i);
                arr.put(new JSONObject().put("name", l[0]).put("id", l[1]).put("type", l[2])
                        .put("user", i >= remote.size()));
            }
        } catch (JSONException ignored) {
        }
        // U+2028 و U+2029 صالحان في JSON لا في شيفرة WebView القديم
        String json = arr.toString().replace("\u2028", " ").replace("\u2029", " ");
        web.evaluateJavascript("setLists(" + json + ");", null);
    }

    // يستخرج {المعرّف, النوع} من رابط يوتيوب (قائمة أو فيديو) أو من معرّف مجرّد
    private static String[] extractTarget(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;
        Matcher m = LIST_PARAM.matcher(input);
        if (m.find()) return new String[]{m.group(1), "p"};
        m = VIDEO_URL.matcher(input);
        if (m.find()) return new String[]{m.group(1), "v"};
        if (LIST_ID.matcher(input).matches()) return new String[]{input, "p"};
        if (VIDEO_ID.matcher(input).matches()) return new String[]{input, "v"};
        return null;
    }

    private static boolean parseRemote(String raw, ArrayList<String[]> out) {
        try {
            String text = raw.trim();
            if (text.startsWith("﻿")) text = text.substring(1);
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.getJSONArray("playlists");
            ArrayList<String[]> tmp = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.getString("name").trim();
                String[] t = null;
                if (o.has("url")) {
                    t = extractTarget(o.getString("url"));
                } else if (o.has("video")) {
                    String v = o.getString("video").trim();
                    if (VIDEO_ID.matcher(v).matches()) t = new String[]{v, "v"};
                } else if (o.has("id")) {
                    String id = o.getString("id").trim();
                    if (VALID_ID.matcher(id).matches()) t = new String[]{id, "p"};
                }
                if (!name.isEmpty() && t != null) {
                    tmp.add(new String[]{name, t[0], t[1]});
                }
            }
            if (tmp.isEmpty()) return false;
            out.clear();
            out.addAll(tmp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // تحميل ملف القوائم من الإنترنت وتحديث القائمة دون مقاطعة التشغيل الحالي
    private void fetchRemote() {
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(CONFIG_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                Tls.apply(activity, c);
                if (c.getResponseCode() != 200) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String raw = bos.toString("UTF-8");

                ArrayList<String[]> parsed = new ArrayList<>();
                if (!parseRemote(raw, parsed)) return;

                prefs().edit().putString(KEY_REMOTE, raw).apply();

                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    remote.clear();
                    remote.addAll(parsed);
                    rebuild();
                    sendLists();
                });
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    // ---------- المشغّل ----------

    private void setupWebView() {
        web = new WebView(activity);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new PageBridge(), "Movies");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // لا نسمح بالخروج من المشغّل المضمّن
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (held) view.evaluateJavascript("window.adhanHold&&adhanHold();", null);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                close();   // انهيار عملية العرض: نغلق اللوحة ويبقى التطبيق يعمل
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
                web.setVisibility(View.INVISIBLE);
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
            }
        };
        web.setWebChromeClient(chrome);
    }

    // ---------- جسر الصفحة (window.Movies) ----------

    private void onUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!destroyed) action.run();
        });
    }

    private final class PageBridge {
        @JavascriptInterface
        public void ready() {
            onUi(() -> {
                pageReady = true;
                sendLists();
                if (held) web.evaluateJavascript("adhanHold();", null);
            });
        }

        @JavascriptInterface
        public void selected(String id) {
            onUi(() -> {
                currentId = id;
                playing = true;
                prefs().edit().putString(KEY_LAST_ID, id).apply();
            });
        }

        @JavascriptInterface
        public void stopped() {
            onUi(() -> {
                currentId = null;
                playing = false;
            });
        }

        @JavascriptInterface
        public void add() {
            onUi(() -> {
                if (!held) showAddDialog();
            });
        }

        @JavascriptInterface
        public void remove(int index) {
            onUi(() -> {
                if (!held && index >= remote.size() && index < lists.size()) confirmDelete(index);
            });
        }

        @JavascriptInterface
        public void episodes() {
            onUi(MoviesPanel.this::showEpisodes);
        }

        @JavascriptInterface
        public void close() {
            onUi(MoviesPanel.this::close);
        }
    }

    // ---------- النوافذ المنبثقة ----------

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView nameLabel = new TextView(activity);
        nameLabel.setText("الاسم");
        EditText nameInput = new EditText(activity);
        nameInput.setSingleLine(true);

        TextView linkLabel = new TextView(activity);
        linkLabel.setText("رابط القائمة أو الفيديو");
        EditText linkInput = new EditText(activity);
        linkInput.setSingleLine(true);

        box.addView(nameLabel);
        box.addView(nameInput);
        box.addView(linkLabel);
        box.addView(linkInput);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("إضافة رابط")
                .setView(box)
                .setPositiveButton("إضافة", (d, w) -> {
                    String[] t = extractTarget(linkInput.getText().toString());
                    if (t == null) {
                        Toast.makeText(activity, "الرابط غير صحيح", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = ("v".equals(t[1]) ? "فيديو " : "قائمة ") + (lists.size() + 1);
                    }
                    user.add(new String[]{name, t[0], t[1]});
                    saveUser();
                    rebuild();
                    sendLists();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmDelete(int pos) {
        dialog = new AlertDialog.Builder(activity)
                .setTitle(lists.get(pos)[0])
                .setMessage("حذف هذا العنصر؟")
                .setPositiveButton("حذف", (d, w) -> {
                    int userIndex = pos - remote.size();
                    if (userIndex < 0 || userIndex >= user.size()) return;
                    user.remove(userIndex);
                    saveUser();
                    rebuild();
                    sendLists();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ---------- اختيار الحلقة ----------

    /** يسأل المشغّل عن عدد فيديوهات القائمة ورقم الحالي، ثم يعرضها للاختيار. */
    void showEpisodes() {
        if (!playing || held || destroyed) return;
        final String id = currentId;
        web.evaluateJavascript("(function(){if(!window.player||!ready)return null;"
                + "var l=player.getPlaylist();return [l?l.length:0,player.getPlaylistIndex()];})()", value -> {
            // ربما بدأ الأذان أو خرج المستخدم قبل وصول الرد
            if (!playing || held || destroyed || id == null || !id.equals(currentId)) return;
            if (dialog != null && dialog.isShowing()) return;
            int count = 0;
            int index = 0;
            try {
                JSONArray a = new JSONArray(value);
                count = a.getInt(0);
                index = a.optInt(1, 0);
            } catch (Exception ignored) {
            }
            if (count < 2) {
                Toast.makeText(activity, "قائمة الحلقات غير متاحة الآن", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[count];
            for (int i = 0; i < count; i++) names[i] = "الحلقة " + (i + 1);
            dialog = new AlertDialog.Builder(activity)
                    .setTitle("اختر الحلقة")
                    .setSingleChoiceItems(names, Math.max(0, Math.min(index, count - 1)), (d, which) -> {
                        d.dismiss();
                        web.evaluateJavascript("errs=0;player.playVideoAt(" + which + ");", null);
                        web.requestFocus();
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
        });
    }

    // ---------- الأذان ----------

    /** بداية الأذان: إيقاف الفيديو في مكانه وإخفاء اللوحة ليظهر الأذان. */
    void hold() {
        if (held || destroyed) return;
        held = true;
        if (dialog != null) {
            dialog.dismiss();   // النافذة المنبثقة تغطي شاشة الأذان
            dialog = null;
        }
        rootFrame.setVisibility(View.GONE);
        final WebView w = web;
        // نوقف عبر المشغّل أولاً (كي يعرف هل كان يعمل) ثم نجمّد الـ WebView احتياطاً
        w.evaluateJavascript("window.adhanHold&&adhanHold();", value -> {
            if (held && !destroyed) w.onPause();
        });
    }

    /** انتهاء الأذان: تعود اللوحة ويكمل الفيديو من حيث توقف. */
    void release() {
        if (!held || destroyed) return;
        held = false;
        rootFrame.setVisibility(View.VISIBLE);
        web.onResume();
        web.evaluateJavascript("window.adhanRelease&&adhanRelease();", null);
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

    void back() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        // الصفحة تخرج من ملء الشاشة أو توقف التشغيل، وإلا نغلقها
        web.evaluateJavascript("window.onBack?onBack():false", value -> {
            if (!"true".equals(value)) close();
        });
    }

    /** تُمرَّر أزرار الريموت إلى الصفحة (onRemote). الضغط المطوّل على «موافق» يحذف رابطاً مضافاً. */
    boolean handleKey(KeyEvent e) {
        final String key;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: key = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE: key = "ok"; break;
            case KeyEvent.KEYCODE_MENU: key = "menu"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: key = "playpause"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: key = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: key = "pause"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: key = "rewind"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: key = "forward"; break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: key = "prevTrack"; break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: key = "nextTrack"; break;
            default: return false;
        }
        if (customView != null) return false;   // ملء شاشة يوتيوب يتعامل مع الأزرار بنفسه
        boolean arrow = "up".equals(key) || "down".equals(key)
                || "left".equals(key) || "right".equals(key);
        // الأسهم عند الضغط (وتتكرر)، والبقية عند رفع الزر كي لا يصل الحدث إلى نافذة منبثقة
        if (arrow ? e.getAction() == KeyEvent.ACTION_DOWN : e.getAction() == KeyEvent.ACTION_UP) {
            boolean longOk = "ok".equals(key) && e.getEventTime() - e.getDownTime() > 700;
            web.evaluateJavascript("window.onRemote&&onRemote('" + (longOk ? "hold" : key) + "');", null);
        }
        return true;
    }

    // ---------- دورة الحياة ----------

    void onPause() {
        if (!destroyed) web.onPause();
    }

    void onResume() {
        if (destroyed) return;
        if (!held) web.onResume();
        if (System.currentTimeMillis() - lastFetch > REFRESH_INTERVAL_MS) fetchRemote();
    }

    void close() {
        if (destroyed) return;
        destroy();
        onClose.run();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (dialog != null) dialog.dismiss();
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
    }
}
