package com.loai.azanhome;

import android.content.Context;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * مستخرج روابط البث (StreamExtractor): يحوّل معرّف قائمة يوتيوب إلى حلقات، ورابط الفيديو إلى رابط بث مباشر
 * يشغّله ExoPlayer. كل الدوال تحجب الخيط فيجب استدعاؤها من خيط خلفي.
 */
final class YoutubeSource {

    static final class Episode {
        final String url;
        final String title;

        Episode(String url, String title) {
            this.url = url;
            this.title = title == null ? "" : title;
        }
    }

    static final class Stream {
        final String url;
        final long expiresAt;

        Stream(String url, long expiresAt) {
            this.url = url;
            this.expiresAt = expiresAt;
        }
    }

    private static final int MAX_EPISODES = 300;
    private static final int MAX_HEIGHT = 720;
    private static final long MAX_AGE_MS = 5L * 3600_000L;
    private static final Pattern EXPIRE = Pattern.compile("[?&]expire=(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private static boolean inited = false;

    private YoutubeSource() {
    }

    private static synchronized void init(Context context) {
        if (inited) return;
        NewPipe.init(new NewPipeDownloader(context), new Localization("ar", "JO"));
        inited = true;
    }

    static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /** حلقات قائمة تشغيل بالترتيب (حتى 300). */
    static List<Episode> playlist(Context context, String playlistId) throws Exception {
        init(context);
        String url = "https://www.youtube.com/playlist?list=" + playlistId;
        List<Episode> out = new ArrayList<>();
        PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
        for (StreamInfoItem it : info.getRelatedItems()) out.add(new Episode(it.getUrl(), it.getName()));
        Page next = info.getNextPage();
        while (next != null && out.size() < MAX_EPISODES) {
            ListExtractor.InfoItemsPage<StreamInfoItem> page =
                    PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next);
            for (StreamInfoItem it : page.getItems()) out.add(new Episode(it.getUrl(), it.getName()));
            next = page.getNextPage();
        }
        if (out.isEmpty()) throw new IOException("القائمة فارغة أو غير متاحة");
        return out;
    }

    /** رابط بث مباشر (فيديو وصوت معاً) بأعلى جودة حتى 720p، وإلا HLS. */
    static Stream resolve(Context context, String videoUrl) throws Exception {
        init(context);
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl);

        VideoStream best = null;
        int bestHeight = 0;
        for (VideoStream v : info.getVideoStreams()) {
            if (v.isVideoOnly() || !v.isUrl()) continue;
            int h = height(v);
            boolean fits = h <= MAX_HEIGHT;
            boolean bestFits = best != null && bestHeight <= MAX_HEIGHT;
            if (best == null
                    || (fits && (!bestFits || h > bestHeight))
                    || (!fits && !bestFits && h < bestHeight)) {
                best = v;
                bestHeight = h;
            }
        }

        String url = best != null ? best.getContent() : info.getHlsUrl();
        if (url == null || url.isEmpty()) throw new IOException("لا يوجد رابط بث متاح لهذا الفيديو");
        return new Stream(url, expiry(url));
    }

    private static int height(VideoStream v) {
        Matcher m = DIGITS.matcher(v.getResolution() == null ? "" : v.getResolution());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static long expiry(String url) {
        long limit = System.currentTimeMillis() + MAX_AGE_MS;
        Matcher m = EXPIRE.matcher(url);
        if (m.find()) {
            try {
                return Math.min(Long.parseLong(m.group(1)) * 1000L, limit);
            } catch (NumberFormatException ignored) {
            }
        }
        return limit;
    }
}
