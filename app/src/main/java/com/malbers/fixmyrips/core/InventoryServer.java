package com.malbers.fixmyrips.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Rip Inventory page, served on the home network while its screen is open. Read-only: it hands out the
 * page, answers "is this CD in my library?" and never writes anything. Plain HTTP on the local network only,
 * so the phone page uses a photo of the barcode (the live camera needs https).
 */
public final class InventoryServer {
    public interface Assets {
        byte[] get(String name) throws IOException;
    }

    private final Http http;
    private final Supplier<LibraryIndex> library;
    private final Assets assets;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket socket;

    public InventoryServer(Http http, Supplier<LibraryIndex> library, Assets assets) {
        this.http = http;
        this.library = library;
        this.assets = assets;
    }

    /** Starts on the first free port from {@code port}; returns the port used. */
    public int start(int port) throws IOException {
        IOException last = null;
        for (int p = port; p < port + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                socket = s;
                Thread t = new Thread(this::loop, "inventory-server");
                t.setDaemon(true);
                t.start();
                return p;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    public void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        pool.shutdownNow();
    }

    private void loop() {
        while (!socket.isClosed()) {
            try {
                Socket c = socket.accept();
                pool.execute(() -> serve(c));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void serve(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(15_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            Response r = parts.length >= 2 && parts[0].equals("GET") ? route(parts[1]) : new Response(405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + r.code + " " + (r.code == 200 ? "OK" : "Error") + "\r\nContent-Type: " + r.type
                    + "\r\nContent-Length: " + r.body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(r.body);
            out.flush();
        } catch (IOException ignored) {
            // the phone went away; nothing to do
        }
    }

    static final class Response {
        final int code;
        final String type;
        final byte[] body;

        Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        static Response json(JSONObject o) {
            return new Response(200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    Response route(String target) {
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        Map<String, String> params = query(q < 0 ? "" : target.substring(q + 1));
        try {
            switch (path) {
                case "/":
                case "/index.html":
                    return new Response(200, "text/html; charset=utf-8", assets.get("inventory.html"));
                case "/zxing.min.js":
                    return new Response(200, "application/javascript", assets.get("zxing.min.js"));
                case "/library": {
                    LibraryIndex lib = library.get();
                    return Response.json(new JSONObject().put("albums", lib.entries.size()).put("updated", lib.updated));
                }
                case "/check": {
                    String code = params.getOrDefault("barcode", "").replaceAll("\\D", "");
                    if (code.length() < 8) return Response.json(new JSONObject().put("error", "That doesn't look like a CD barcode."));
                    try {
                        return Response.json(Inventory.check(http, library.get(), code).toJson().put("barcode", code));
                    } catch (IOException e) {
                        return Response.json(new JSONObject().put("error", "Couldn't reach MusicBrainz. Try again in a moment."));
                    }
                }
                case "/find":
                    return Response.json(Inventory.find(library.get(), params.getOrDefault("q", "")).toJson());
                default:
                    return new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            return new Response(500, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    static Map<String, String> query(String q) {
        Map<String, String> out = new HashMap<>();
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(kv.substring(0, eq).toLowerCase(Locale.ROOT), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
                // skip a malformed pair
            }
        }
        return out;
    }
}
