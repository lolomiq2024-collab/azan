package com.loai.azanhome;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** منطق المتصفح الذي لا يعتمد على أندرويد: تحويل ما يكتبه المستخدم إلى رابط، وصفحة البداية. */
final class BrowserPages {

    /** عنوان وهمي لصفحة البداية: النطاق .invalid لا وجود له، ويقدّم التطبيق محتواه بنفسه. */
    static final String HOME_HOST = "home.azan.invalid";
    static final String HOME_URL = "https://" + HOME_HOST + "/";

    private static final String SEARCH = "https://www.google.com/search?safe=active&q=";

    private static final Pattern ADDRESS = Pattern.compile(
            "^(?:((?:\\d{1,3}\\.){3}\\d{1,3}|localhost)"
                    + "|(?:[\\p{L}\\p{M}\\p{N}_-]+\\.)+\\p{L}[\\p{L}\\p{M}\\p{N}-]+\\.?)"
                    + "(?::\\d{1,5})?(?:[/?#]\\S*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final String[][] SHORTCUTS = {
            {"YouTube", "https://www.youtube.com/", "#c4302b"},
            {"Google", "https://www.google.com/webhp?safe=active", "#2b5fb8"},
            {"ويكيبيديا", "https://ar.wikipedia.org/", "#5b6470"},
            {"الجزيرة نت", "https://www.aljazeera.net/", "#a8741a"},
            {"القرآن الكريم", "https://quran.com/ar", "#14806f"},
            {"رؤيا", "https://roya.tv/", "#7a2d8f"},
    };

    private BrowserPages() {
    }

    static boolean isWebUrl(String url) {
        if (url == null) return false;
        return url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8);
    }

    /** رابط كامل كما هو، عنوان موقع يُسبق بـ https، وغير ذلك يصبح بحثاً. null إن كان النص فارغاً. */
    static String toUrl(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (isWebUrl(s)) return s;
        Matcher m = ADDRESS.matcher(s);
        if (m.matches()) {
            // أجهزة الشبكة المحلية (راوتر...) تعمل غالباً بـ http فقط
            return (m.group(1) != null ? "http://" : "https://") + s;
        }
        try {
            return SEARCH + URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** صفحة البداية. favorites: أزواج {العنوان, الرابط}. */
    static String startPage(List<String[]> favorites) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"ar\" dir=\"rtl\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>الصفحة الرئيسية</title><style>")
                .append("body{margin:0;padding:24px 16px;background:#08101f;color:#fff;font-family:sans-serif;text-align:center}")
                .append("h1{margin:0 0 18px;font-size:26px}h2{margin:26px 0 12px;font-size:18px;color:#9fb4d8}")
                .append("form{display:flex;max-width:620px;margin:0 auto;gap:8px}")
                .append("input{flex:1;min-width:0;font-size:18px;padding:12px 16px;border-radius:28px;border:0;outline:0}")
                .append("button{font-size:18px;padding:12px 24px;border-radius:28px;border:0;background:#2b7de9;color:#fff}")
                .append(".g{display:flex;flex-wrap:wrap;justify-content:center;gap:14px;max-width:760px;margin:0 auto}")
                .append(".c{box-sizing:border-box;width:220px;padding:24px 10px;border-radius:18px;color:#fff;text-decoration:none;")
                .append("font-size:19px;font-weight:bold;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}")
                .append(".f{background:#1b2a47}p{color:#7f93b8;font-size:14px;line-height:1.9;margin-top:28px}")
                .append("</style></head><body><h1>المتصفح</h1>")
                .append("<form action=\"https://www.google.com/search\" method=\"get\">")
                .append("<input type=\"hidden\" name=\"safe\" value=\"active\">")
                .append("<input name=\"q\" placeholder=\"ابحث في Google\" autocomplete=\"off\">")
                .append("<button type=\"submit\">بحث</button></form>")
                .append("<h2>مواقع</h2><div class=\"g\">");
        for (String[] s : SHORTCUTS) {
            h.append("<a class=\"c\" style=\"background:").append(s[2]).append("\" href=\"")
                    .append(escape(s[1])).append("\">").append(escape(s[0])).append("</a>");
        }
        h.append("</div><h2>المفضلة</h2>");
        if (favorites.isEmpty()) {
            h.append("<p>لا توجد مواقع بعد. افتح أي موقع ثم اضغط ☆ في الشريط العلوي لإضافته.</p>");
        } else {
            h.append("<div class=\"g\">");
            for (String[] f : favorites) {
                if (!isWebUrl(f[1])) continue;
                h.append("<a class=\"c f\" dir=\"auto\" href=\"").append(escape(f[1])).append("\">")
                        .append(escape(f[0])).append("</a>");
            }
            h.append("</div>");
        }
        h.append("<p>الأسهم: تحريك المؤشر (الاستمرار عند الحافة يمرّر الصفحة) • موافق: نقر<br>")
                .append("رجوع: الصفحة السابقة • ضغطة مطوّلة على رجوع: إغلاق المتصفح • القائمة: شريط العنوان</p>")
                .append("</body></html>");
        return h.toString();
    }
}
