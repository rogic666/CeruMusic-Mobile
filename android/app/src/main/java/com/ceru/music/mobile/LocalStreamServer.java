package com.ceru.music.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny loopback HTTP server used to hand SAF (content://) files to the WebView.
 *
 * <p>The WebView audio element cannot play {@code content://} URIs, and copying
 * every file into the app cache would double the storage used by downloads, so
 * the bytes are streamed from the document provider on demand. Range requests
 * are supported so seeking keeps working.</p>
 *
 * <p>Only loopback connections are accepted and every resource needs an
 * unguessable token, so other apps cannot enumerate the user's library.</p>
 */
final class LocalStreamServer {

    interface Source {
        InputStream open() throws IOException;

        long size();
    }

    private static final class Resource {
        final Source source;
        final String type;

        Resource(Source source, String type) {
            this.source = source;
            this.type = type;
        }
    }

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private ServerSocket socket;
    private Thread acceptor;
    private int port;

    synchronized int port() {
        return port;
    }

    synchronized boolean isRunning() {
        return socket != null && !socket.isClosed();
    }

    synchronized void start() throws IOException {
        if (isRunning()) return;
        ServerSocket server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        socket = server;
        port = server.getLocalPort();
        acceptor = new Thread(() -> {
            while (isRunning()) {
                try {
                    Socket client = server.accept();
                    workers.execute(() -> handle(client));
                } catch (IOException error) {
                    break;
                }
            }
        }, "ceru-local-stream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void publish(String token, Source source, String type) throws IOException {
        start();
        resources.put(token, new Resource(source, type));
    }

    void forget(String token) {
        resources.remove(token);
    }

    synchronized void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        port = 0;
        resources.clear();
    }

    private void handle(Socket client) {
        try (Socket connection = client) {
            connection.setSoTimeout(20_000);
            InputStream input = connection.getInputStream();
            OutputStream output = connection.getOutputStream();
            String head = readHead(input);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return;
            String[] request = lines[0].split(" ");
            if (request.length < 2) return;
            String target = request[1];
            int query = target.indexOf('?');
            if (query >= 0) target = target.substring(0, query);
            String token = target.startsWith("/s/") ? target.substring(3) : "";
            long rangeStart = -1;
            long rangeEnd = -1;
            for (int index = 1; index < lines.length; index += 1) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (!"range".equals(name)) continue;
                long[] parsed = parseRange(line.substring(colon + 1).trim());
                if (parsed != null) {
                    rangeStart = parsed[0];
                    rangeEnd = parsed[1];
                }
            }
            Resource resource = resources.get(token);
            if (resource == null) {
                writeHead(output, "404 Not Found", "text/plain; charset=utf-8", 9);
                output.write("not found".getBytes(StandardCharsets.UTF_8));
                output.flush();
                return;
            }
            long size = resource.source.size();
            long start = 0;
            long end = size > 0 ? size - 1 : -1;
            boolean hasRange = rangeStart >= 0 || (rangeStart == -1 && rangeEnd > 0);
            if (hasRange && size > 0) {
                start = rangeStart < 0 ? Math.max(0, size - rangeEnd) : Math.min(rangeStart, size - 1);
                end = rangeEnd < 0 ? size - 1 : Math.min(Math.max(rangeEnd, start), size - 1);
            }
            boolean partial = hasRange && size > 0;
            if (partial) {
                StringBuilder head2 = new StringBuilder();
                head2.append("HTTP/1.1 206 Partial Content\r\n");
                head2.append("Access-Control-Allow-Origin: *\r\n");
                head2.append("Accept-Ranges: bytes\r\n");
                head2.append("Content-Type: ").append(resource.type).append("\r\n");
                head2.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(size).append("\r\n");
                head2.append("Content-Length: ").append(end - start + 1).append("\r\n");
                head2.append("Cache-Control: no-store\r\n");
                head2.append("Connection: close\r\n\r\n");
                output.write(head2.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                writeHead(output, "200 OK", resource.type, size);
            }
            output.flush();

            long remaining = size > 0 ? end - start + 1 : Long.MAX_VALUE;
            try (InputStream body = resource.source.open()) {
                skipFully(body, start);
                byte[] buffer = new byte[32 * 1024];
                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int read = body.read(buffer, 0, want);
                    if (read <= 0) break;
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                output.flush();
            }
        } catch (Exception ignored) {
            // A broken pipe just means the player seeked or stopped.
        }
    }

    private static void writeHead(OutputStream output, String status, String type, long length)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static long[] parseRange(String value) {
        if (value == null || !value.startsWith("bytes=")) return null;
        String spec = value.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String from = spec.substring(0, dash).trim();
            String to = spec.substring(dash + 1).trim();
            long start = from.isEmpty() ? -1 : Long.parseLong(from);
            long end = to.isEmpty() ? -1 : Long.parseLong(to);
            if (start < 0 && end <= 0) return null;
            if (start < 0) {
                // Suffix range: the last N bytes.
                return new long[] { -1, end };
            }
            return new long[] { start, end };
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[8 * 1024];
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int read = input.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read <= 0) return;
            remaining -= read;
        }
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int a = -1;
        int b = -1;
        int c = -1;
        int d = -1;
        int value;
        while ((value = input.read()) != -1) {
            buffer.write(value);
            a = b;
            b = c;
            c = d;
            d = value;
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
            if (buffer.size() > 16 * 1024) break;
        }
        if (buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.ISO_8859_1.name());
    }
}
