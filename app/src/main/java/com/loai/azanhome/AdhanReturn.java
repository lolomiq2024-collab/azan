package com.loai.azanhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * منبّه نظام يعيد التطبيق إلى الواجهة وقت الأذان أثناء وجود المستخدم في تطبيق يوتيوب.
 * صفحة التطبيق وهي في الخلفية قد تتأخر مؤقتاتها أو تُغلق عمليتها، فلا يكفي الاعتماد عليها.
 */
final class AdhanReturn {

    static final String ACTION = "com.loai.azanhome.ADHAN_RETURN";
    static final String EXTRA_APP = "app";
    static final String EXTRA_AT = "at";
    private static final int REQUEST = 2;

    private AdhanReturn() {
    }

    private static Intent intent(Context context) {
        return new Intent(context, MainActivity.class)
                .setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    static void schedule(Context context, long at, String app) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null || at <= System.currentTimeMillis()) return;
        PendingIntent pi = PendingIntent.getActivity(context, REQUEST,
                intent(context).putExtra(EXTRA_APP, app).putExtra(EXTRA_AT, at),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            // أندرويد 12 فما فوق قد يمنع المنبّه الدقيق
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getActivity(context, REQUEST, intent(context),
                PendingIntent.FLAG_NO_CREATE | immutable());
        if (am != null && pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }
}
