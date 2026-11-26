package com.loai.azanhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * أفلام ومسلسلات: قوائم يوتيوب تُعرض فوق الصفحة بملء الشاشة داخل MainActivity.
 * عند الأذان تُخفى اللوحة ويتوقف الفيديو (hold)، وبعد انتهائه تعود ويكمل من مكانه (release).
 */
final class MoviesPanel {

    // رابط ملف القوائم على GitHub: عدّل الملف هناك فتتحدث القوائم تلقائياً دون إعادة بناء التطبيق
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/lolomiq2024-collab/yt-playlists/main/playlists.json";

    // عناصر احتياطية تُستخدم فقط قبل أول تحميل ناجح للملف: {الاسم, المعرّف, النوع}
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

    private static final int BG = 0xFF121212;
    private static final int ACCENT = 0xFFF5B301;

    private static final Pattern LIST_PARAM = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern VIDEO_URL =
            Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern LIST_ID = Pattern.compile("^(PL|UU|OL|FL|LL)[A-Za-z0-9_-]{10,}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{10,}$");

    private final ArrayList<String[]> remote = new ArrayList<>();
    private final ArrayList<String[]> user = new ArrayList<>();
    private final ArrayList<String[]> lists = new ArrayList<>();
    private int current = -1;
    private String currentId = null;
    private boolean playing = false;
    private long lastFetch = 0;

    private final Activity activity;
    private final Runnable onClose;
    private boolean held = false;       // الأذان يعمل الآن
    private boolean destroyed = false;
    private AlertDialog dialog;
    private ListView listView;

    private final FrameLayout rootFrame;
    private LinearLayout content;
    private FrameLayout playerHolder;
    private WebView web;
    private BaseAdapter adapter;

    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    MoviesPanel(Activity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;

        loadLists();
        setupAdapter();
        setupWebView();

        rootFrame = new FrameLayout(activity);
        rootFrame.setBackgroundColor(BG);
        rootFrame.setClickable(true);   // لا تمرّ اللمسات إلى الصفحة التي تحتها
        buildLayout();

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
            for (String[] d : DEFAULTS) remote.add(d);
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
        current = indexOfId(currentId);
    }

    private int indexOfId(String id) {
        if (id == null) return -1;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i)[1].equals(id)) return i;
        }
        return -1;
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
                    adapter.notifyDataSetChanged();
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
                content.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootFrame.removeView(customView);
                customView = null;
                content.setVisibility(View.VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        };
        web.setWebChromeClient(chrome);
    }

    // type: p = قائمة تشغيل ، v = فيديو مفرد (يعاد تشغيله تلقائياً عند انتهائه)
    private static String playerHtml(String id, String type) {
        boolean video = "v".equals(type);
        String src = video ? "videoId:'" + id + "'," : "";
        String vars = video
                ? "loop:1,playlist:'" + id + "',"
                : "listType:'playlist',list:'" + id + "',loop:1,";
        return "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;position:relative}"
                + "#p{width:100%;height:100%}</style></head><body><div id=\"p\"></div>"
                + "<script src=\"https://www.youtube.com/iframe_api\"></script>"
                + "<script>"
                + "var player,ready=false,errs=0,hold=false,again=false;"
                // الأذان: إيقاف مؤقت ثم إكمال (لا يُستأنف ما أوقفه المستخدم بنفسه)
                + "function st(){try{return ready?player.getPlayerState():-1;}catch(x){return -1;}}"
                + "function halt(){try{if(ready)player.pauseVideo();}catch(x){}}"
                + "function adhanHold(){if(hold)return;hold=true;again=st()!==2;halt();}"
                + "function adhanRelease(){if(!hold)return;hold=false;"
                + "if(again){try{if(ready)player.playVideo();}catch(x){}}}"
                + "setInterval(function(){var s=st();if(hold&&(s===1||s===3))halt();},400);"
                // تكبير إطار المشغّل ليملأ الشاشة كاملة (تغطية) بدل بقاء الصورة في الوسط
                + "function fit(){var f=document.querySelector('iframe');if(!f)return;"
                + "var W=window.innerWidth,H=window.innerHeight,w=Math.max(W,H*16/9),h=w*9/16;"
                + "f.style.position='absolute';f.style.width=w+'px';f.style.height=h+'px';"
                + "f.style.left=((W-w)/2)+'px';f.style.top=((H-h)/2)+'px';}"
                + "window.addEventListener('resize',fit);"
                + "function onYouTubeIframeAPIReady(){"
                + "player=new YT.Player('p',{width:'100%',height:'100%'," + src
                + "playerVars:{" + vars + "autoplay:1,playsinline:1,rel:0,enablejsapi:1,"
                + "origin:'" + ORIGIN + "',widget_referrer:'" + ORIGIN + "'},"
                + "events:{"
                + "onReady:function(e){ready=true;fit();if(!hold)e.target.playVideo();setTimeout(fit,500);},"
                + "onStateChange:function(e){fit();if(hold){if(e.data===1||e.data===3)halt();return;}if(e.data===1){errs=0;}"
                + (video ? "if(e.data===0){try{player.seekTo(0);player.playVideo();}catch(x){}}" : "")
                + "},"
                + "onError:function(e){if(hold)return;errs++;if(errs<60){try{player.nextVideo();}catch(x){}}}"
                + "}});}"
                + "</script></body></html>";
    }

    private void play(int index) {
        if (index < 0 || index >= lists.size()) return;
        String[] item = lists.get(index);
        current = index;
        currentId = item[1];
        prefs().edit().putString(KEY_LAST_ID, currentId).apply();
        adapter.notifyDataSetChanged();
        playing = true;
        buildLayout();
        web.requestFocus();
        web.loadDataWithBaseURL(ORIGIN, playerHtml(item[1], item[2]), "text/html", "utf-8", null);
    }

    // العودة إلى الصفحة الرئيسية وإيقاف التشغيل
    private void goHome() {
        playing = false;
        currentId = null;
        current = -1;
        web.loadUrl("about:blank");
        buildLayout();
        adapter.notifyDataSetChanged();
        listView.requestFocus();
    }

    // ---------- الواجهة ----------

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void setupAdapter() {
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return lists.size();
            }

            @Override
            public Object getItem(int i) {
                return lists.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                TextView t = (convert instanceof TextView) ? (TextView) convert : new TextView(activity);
                t.setPadding(dp(16), dp(14), dp(16), dp(14));
                t.setTextSize(18);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                boolean sel = i == current;
                t.setText((sel ? "▶  " : "") + lists.get(i)[0]);
                t.setTextColor(sel ? ACCENT : Color.WHITE);
                t.setBackgroundColor(sel ? 0xFF262626 : Color.TRANSPARENT);
                return t;
            }
        };
    }

    private void buildLayout() {
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        if (content != null) rootFrame.removeView(content);

        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        playerHolder = new FrameLayout(activity);
        playerHolder.setBackgroundColor(Color.BLACK);
        playerHolder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView toggle = new TextView(activity);
        toggle.setText("☰");
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(22);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackgroundColor(0x66000000);
        toggle.setOnClickListener(v -> goHome());
        playerHolder.addView(toggle, new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);

        Button add = new Button(activity);
        add.setText("+ إضافة رابط");
        add.setTextColor(Color.BLACK);
        add.setBackground(focusable(ACCENT));
        add.setOnClickListener(v -> showAddDialog());
        Button exit = new Button(activity);
        exit.setText("✕ رجوع");
        exit.setTextColor(Color.WHITE);
        exit.setBackground(focusable(0xFF333333));
        exit.setOnClickListener(v -> close());

        TextView title = new TextView(activity);
        title.setText("أفلام ومسلسلات");
        title.setTextColor(ACCENT);
        title.setTextSize(24);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(add);
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exitLp.setMarginStart(dp(12));
        header.addView(exit, exitLp);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(dp(16), dp(12), dp(16), dp(8));
        side.addView(header, headerLp);

        listView = new ListView(activity);
        listView.setSelector(new ColorDrawable(0x66F5B301));
        listView.setAdapter(adapter);
        listView.setDivider(new ColorDrawable(0xFF2A2A2A));
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((p, v, pos, id) -> play(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= remote.size()) confirmDelete(pos);
            return true;
        });
        side.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (playing) {
            content.addView(playerHolder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            content.addView(side, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        rootFrame.addView(content, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (customView != null) content.setVisibility(View.GONE);
    }

    /** خلفية زر بإطار أبيض عند التركيز عليه بالريموت. */
    private StateListDrawable focusable(int color) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(color);
        on.setStroke(dp(3), Color.WHITE);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, new ColorDrawable(color));
        return d;
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
                    adapter.notifyDataSetChanged();
                    play(lists.size() - 1);
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
                    boolean wasCurrent = pos == current;
                    user.remove(userIndex);
                    saveUser();
                    rebuild();
                    if (wasCurrent) {
                        currentId = null;
                        current = -1;
                    }
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("إلغاء", null)
                .show();
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
        else if (playing) web.requestFocus();
        else if (listView != null) listView.requestFocus();
    }

    // ---------- الريموت ----------

    void back() {
        if (customView != null) {
            chrome.onHideCustomView();
        } else if (playing) {
            goHome();
        } else {
            close();
        }
    }

    /** أثناء التشغيل: موافق = تشغيل/إيقاف، ◄► = تقديم وتأخير، ▲▼ = السابق والتالي. */
    boolean handleKey(KeyEvent e) {
        if (!playing) return false;
        final String code;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                code = "st()===1?player.pauseVideo():player.playVideo()";
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                code = "player.playVideo()";
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                code = "player.pauseVideo()";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                code = "player.seekTo(Math.max(0,player.getCurrentTime()-10),true)";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                code = "player.seekTo(player.getCurrentTime()+10,true)";
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                code = "player.previousVideo()";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                code = "player.nextVideo()";
                break;
            default:
                return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            web.evaluateJavascript("if(window.player&&ready){" + code + ";}", null);
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
