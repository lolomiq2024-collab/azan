package com.loai.azanhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** ملف صغير يحفظ الرابط الجاري تشغيله؛ بقاؤه يعني أن عملية المشغّل انهارت. */
final class CrashMarker {

    private CrashMarker() {
    }

    static void write(File f, String url) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(url.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (IOException ignored) {
        }
    }

    /** يقرأ الرابط ويحذف الملف. يعيد null إن لم يوجد. */
    static String take(File f) {
        if (!f.exists()) return null;
        byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
        } catch (IOException e) {
            return null;
        } finally {
            clear(f);
        }
    }

    static void clear(File f) {
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }
}
