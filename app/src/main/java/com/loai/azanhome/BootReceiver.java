package com.loai.azanhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** يشغّل التطبيق تلقائياً عند إقلاع الجهاز، مع محاولة ثانية متأخرة. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        launch(context);

        // محاولة ثانية بعد 8 ثوانٍ: بعض الشاشات تُظهر واجهتها الأصلية بعد الإقلاع فتغطي التطبيق
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getActivity(
                    context, 1, buildIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) {
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 8000, pi);
            }
        } catch (Exception ignored) {
        }
    }

    private static Intent buildIntent(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static void launch(Context context) {
        try {
            context.startActivity(buildIntent(context));
        } catch (Exception ignored) {
        }
    }
}
