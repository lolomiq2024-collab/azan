package com.loai.azanhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * خادم صغير على الجهاز نفسه (127.0.0.1) يقدّم صفحة المشغّل لمحرك Firefox:
 * يوتيوب يحتاج صفحة لها عنوان ويب، والتطبيق يحتاج طريقة لإرسال أوامر الريموت إليها.
 * كل طلب يجب أن يبدأ برمز سري عشوائي، فلا تستطيع تطبيقات أخرى التحكم بالمشغّل.
 * لا يعتمد على أندرويد.
 */
final class PlayerServer {

    /** يُستدعى من خيط الخادم. */
    interface Listener {
        void onState(int gen, String state);

        void onEpisodes(int gen, int count, int index);
    }

    interface Page {
        String html(int gen);
    }

    private final Listener listener;
    private final ServerSocket socket;
    private final String token;
    private final long pollMs;
    private final List<String> commands = new ArrayList<>();
    private String page;
    private int gen = 0;
    private boolean closed = false;

    PlayerServer(Listener listener) throws IOException {
        this(listener, 20000);
    }

    PlayerServer(Listener listener, long pollMs) throws IOException {
        this.listener = listener;
        this.pollMs = pollMs;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder b = new StringBuilder();
        for (byte x : random) b.append(String.format((java.util.Locale) null, "%02x", x & 0xff));
        token = b.toString();
        socket = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread accept = new Thread(this::acceptLoop, "player-server");
        accept.setDaemon(true);
        accept.start();
    }

    String origin() {
        return "http://localhost:" + socket.getLocalPort();
    }

    /** بادئة كل روابط المشغّل. */
    String base() {
        return origin() + "/" + token + "/";
    }

    /** صفحة جديدة: تُلغى أوامر الصفحة السابقة. يعيد رابطها. */
    synchronized String load(Page source) {
        gen++;
        commands.clear();
        page = source == null ? null : source.html(gen);
        notifyAll();
        return base() + "p?g=" + gen;
    }

    synchronized int generation() {
        return gen;
    }

    synchronized void send(String command) {
        commands.add(command);
        notifyAll();
    }

    void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (true) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (socket.isClosed()) return;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException stop) {
                    return;
                }
                continue;
            }
            Thread t = new Thread(() -> serve(client), "player-request");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
            String request = in.readLine();
            if (request == null) return;
            String header;
            for (int i = 0; i < 100 && (header = in.readLine()) != null && !header.isEmpty(); i++) {
                // لا نحتاج الترويسات، لكن يجب قراءتها قبل الرد
            }
            String[] parts = request.split(" ");
            String prefix = "/" + token + "/";
            if (parts.length < 2 || !"GET".equals(parts[0]) || !parts[1].startsWith(prefix)) {
                reply(c, "404 Not Found", "text/plain", "");
                return;
            }
            String path = parts[1].substring(prefix.length());
            int q = path.indexOf('?');
            String name = q < 0 ? path : path.substring(0, q);
            Map<String, String> args = new HashMap<>();
            if (q >= 0) {
                for (String pair : path.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) args.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            int g = number(args.get("g"));
            switch (name) {
                case "p": {
                    String html;
                    synchronized (this) {
                        html = g == gen ? page : null;
                    }
                    if (html == null) reply(c, "404 Not Found", "text/plain", "");
                    else reply(c, "200 OK", "text/html", html);
                    break;
                }
                case "c":
                    reply(c, "200 OK", "text/plain", waitForCommands(g));
                    break;
                case "s":
                    reply(c, "200 OK", "text/plain", "");
                    if (args.get("k") != null) listener.onState(g, args.get("k"));
                    break;
                case "e":
                    reply(c, "200 OK", "text/plain", "");
                    listener.onEpisodes(g, number(args.get("c")), number(args.get("i")));
                    break;
                default:
                    reply(c, "404 Not Found", "text/plain", "");
            }
        } catch (IOException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** ينتظر أمراً للصفحة الحالية: سطر لكل أمر، أو "gone" إن لم تعد هي الحالية. */
    private synchronized String waitForCommands(int g) throws InterruptedException {
        long end = System.nanoTime() + pollMs * 1000000L;
        while (!closed && g == gen && commands.isEmpty()) {
            long left = (end - System.nanoTime()) / 1000000L;
            if (left <= 0) break;
            wait(left);
        }
        if (closed || g != gen) return "gone";
        StringBuilder b = new StringBuilder();
        for (String command : commands) b.append(command).append('\n');
        commands.clear();
        return b.toString();
    }

    private static int number(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void reply(Socket c, String status, String type, String body) throws IOException {
        byte[] data = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = c.getOutputStream();
        out.write(head.getBytes("ISO-8859-1"));
        out.write(data);
        out.flush();
    }
}
