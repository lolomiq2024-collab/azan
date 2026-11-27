package com.loai.azanhome;

/**
 * حالة «المستخدم في تطبيق يوتيوب» ومتى يعود التطبيق للأذان ثم يرجع إليه.
 * منطق صرف بلا أندرويد.
 */
final class AwayState {

    private String app;           // التطبيق المفتوح فوقنا، أو null
    private boolean forAdhan;     // عدنا للواجهة بسبب الأذان وسنرجع إليه بعده

    /** فُتح تطبيق يوتيوب من القوائم. */
    void opened(String packageName) {
        app = packageName;
        forAdhan = false;
    }

    /** بدأ الأذان. true إن كان المستخدم في يوتيوب ويجب إحضار تطبيقنا للواجهة. */
    boolean adhanStarted() {
        if (app == null) return false;
        forAdhan = true;
        return true;
    }

    /** منبّه النظام أعادنا للواجهة (قد تكون عملية التطبيق أُعيد تشغيلها). */
    void alarm(String packageName) {
        if (packageName == null) return;
        app = packageName;
        forAdhan = true;
    }

    /** ظهر تطبيقنا. true إن عاد المستخدم بنفسه: لا رجوع بعدها إلى يوتيوب. */
    boolean resumed() {
        if (forAdhan) return false;
        app = null;
        return true;
    }

    /** انتهى الأذان. يعيد التطبيق الذي نرجع إليه، أو null. */
    String adhanEnded(boolean inFront) {
        if (!forAdhan) return null;
        forAdhan = false;
        if (!inFront) app = null;   // المستخدم في مكان آخر: لا نسحبه
        return app;
    }

    /** عدنا للأذان لكنه لم يبدأ. */
    void expire() {
        app = null;
        forAdhan = false;
    }

    boolean waitingForAdhan() {
        return forAdhan;
    }
}
