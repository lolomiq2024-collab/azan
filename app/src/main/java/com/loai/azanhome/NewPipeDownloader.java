package com.loai.azanhome;

import android.content.Context;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** طبقة الشبكة التي تطلبها مكتبة NewPipeExtractor. تعتمد على HttpURLConnection مع جذور شهادات الأجهزة القديمة (Tls). */
final class NewPipeDownloader extends Downloader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private final Context context;

    NewPipeDownloader(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        try {
            Tls.apply(context, c);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod(request.httpMethod());
            c.setRequestProperty("User-Agent", USER_AGENT);

            for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                String name = h.getKey();
                // لا نمرّر Accept-Encoding كي يفك HttpURLConnection الضغط بنفسه
                if (name == null || "Accept-Encoding".equalsIgnoreCase(name)) continue;
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) c.setRequestProperty(name, v);
                    else c.addRequestProperty(name, v);
                    first = false;
                }
            }

            byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }

            int code = c.getResponseCode();
            if (code == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
            }

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    body = bos.toString("UTF-8");
                }
            }

            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), headers, body, c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }
}
