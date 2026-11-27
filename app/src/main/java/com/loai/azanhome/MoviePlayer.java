package com.loai.azanhome;

import android.view.View;

/** المشغّل الداخلي للقوائم: WebView أو محرك Firefox. كل الدوال تُستدعى من خيط الواجهة. */
interface MoviePlayer {

    /** عدد فيديوهات القائمة ورقم الحالي؛ العدد 0 إن لم يكن معروفاً. */
    interface Episodes {
        void result(int count, int index);
    }

    View view();

    void load(String id, String type);

    /** أمر من صفحة المشغّل: toggle, play, pause, back, forward, prev, next, at:رقم */
    void command(String command);

    void episodes(Episodes callback);

    /** بداية الأذان: يتوقف الفيديو في مكانه. */
    void hold();

    /** انتهاء الأذان: يكمل من حيث توقف. */
    void release();

    void onPause();

    void onResume();

    /** يوقف التشغيل مع بقاء المشغّل صالحاً لعنصر آخر. */
    void stop();

    void destroy();
}
