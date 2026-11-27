package com.loai.azanhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** تطبيق يوتيوب المثبّت على الجهاز: العثور عليه وفتح قائمة أو فيديو فيه. */
final class YouTubeApp {

    private static final String[] KNOWN = {
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube",
    };

    private YouTubeApp() {
    }

    private static boolean installed(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** التطبيقات التي تعلن أنها تفتح روابط يوتيوب: أزواج {الاسم, الحزمة}. */
    static List<String[]> candidates(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String[]> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String scheme : new String[]{"https", "http"}) {
            Intent probe = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(scheme + "://www.youtube.com/watch?v=aqz-KE-bpKQ"));
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                if (ri.activityInfo == null) continue;
                String name = ri.activityInfo.packageName;
                if (context.getPackageName().equals(name) || seen.contains(name)) continue;
                seen.add(name);
                out.add(new String[]{String.valueOf(ri.loadLabel(pm)), name});
            }
        }
        return out;
    }

    /** chosen: ما اختاره المستخدم سابقاً. يعيد null إن لم يُعرف تطبيق يوتيوب. */
    static String find(Context context, String chosen) {
        if (chosen != null && installed(context, chosen)) return chosen;
        for (String name : KNOWN) {
            if (installed(context, name)) return name;
        }
        for (String[] c : candidates(context)) {
            if (c[1].toLowerCase(Locale.ROOT).contains("youtube")) return c[1];
        }
        return null;
    }

    private static Intent launcher(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (i == null) i = pm.getLaunchIntentForPackage(packageName);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return i;
    }

    private static boolean start(Context context, Intent intent) {
        if (intent == null) return false;
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** يعيد تطبيق يوتيوب إلى الواجهة (بعد الأذان). */
    static boolean resume(Context context, String packageName) {
        return start(context, launcher(context, packageName));
    }

    enum Result { OPENED_LINK, OPENED_APP_ONLY, FAILED }

    /** type: p = قائمة تشغيل ، v = فيديو مفرد */
    static Result open(Context context, String packageName, String id, String type) {
        String path = "v".equals(type) ? "watch?v=" + id : "playlist?list=" + id;
        for (String scheme : new String[]{"https", "http"}) {
            Intent view = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(scheme + "://www.youtube.com/" + path))
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(context, view)) return Result.OPENED_LINK;
        }
        return resume(context, packageName) ? Result.OPENED_APP_ONLY : Result.FAILED;
    }
}
