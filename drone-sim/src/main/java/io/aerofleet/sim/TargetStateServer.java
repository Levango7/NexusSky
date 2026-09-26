package io.aerofleet.sim;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ground-truth HTTP sidecar of the simulator: exposes the synthetic-target
 * world state and the camera-shot metadata for the cloud backend and the
 * GCS. Uses the JDK built-in HttpServer - zero extra dependencies.
 *
 *   GET /targets        -> [{id, kind, lat, lon, speedMps, headingDeg}, ...]
 *   GET /camera/shots   -> [{frameSeq, ..., sizeBytes, targets:[...]}, ...]
 *   GET /camera/shots/{frameSeq}.jpg -> real JPEG bytes (E2)
 *   GET /health         -> {ok: true, uptimeSec}
 *   GET /radio          -> {rssiDbm: ...}
 *
 * Bound to loopback by default (a sim-world truth channel, not a public API).
 */
public final class TargetStateServer {

    private final TargetSimulator targets;
    private final long bootMs = System.currentTimeMillis();
    private final Supplier<List<CameraModel.Shot>> shotSource;
    /** Live RSSI sample (E1: link geometry debug/e2e assertions). */
    private final Supplier<Double> rssiSource;
    /** JPEG bytes per frameSeq (E2: real image payload). */
    private final Function<Long, byte[]> jpegSource;
    private HttpServer server;
    /** P3-fix(Minor): 保存 executor 引用以便优雅关闭，避免线程泄漏 */
    private ExecutorService executor;
    private final int port;

    public TargetStateServer(TargetSimulator targets, int port,
                             Supplier<List<CameraModel.Shot>> shotSource) {
        this(targets, port, shotSource, () -> 0.0, seq -> null);
    }

    public TargetStateServer(TargetSimulator targets, int port,
                             Supplier<List<CameraModel.Shot>> shotSource,
                             Supplier<Double> rssiSource) {
        this(targets, port, shotSource, rssiSource, seq -> null);
    }

    public TargetStateServer(TargetSimulator targets, int port,
                             Supplier<List<CameraModel.Shot>> shotSource,
                             Supplier<Double> rssiSource,
                             Function<Long, byte[]> jpegSource) {
        this.targets = targets;
        this.port = port;
        this.shotSource = shotSource;
        this.rssiSource = rssiSource;
        this.jpegSource = jpegSource;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "target-state-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/targets", ex -> respond(ex, targets.snapshotJson()));
        server.createContext("/camera/shots", this::shotsOrJpeg);
        server.createContext("/radio", ex -> respond(ex,
                String.format("{\"rssiDbm\":%.1f}", rssiSource.get())));
        server.createContext("/health", ex -> respond(ex,
                "{\"ok\":true,\"uptimeSec\":" + (System.currentTimeMillis() - bootMs) / 1000 + "}"));
        server.start();
        SimLog.info("ground-truth HTTP on 127.0.0.1:" + port
                + " (/targets /camera/shots[/{seq}.jpg] /radio /health)");
    }

    /** /camera/shots -> JSON; /camera/shots/{seq}.jpg -> JPEG bytes. */
    private void shotsOrJpeg(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith(".jpg") && path.length() > "/camera/shots/".length()) {
            String tail = path.substring(path.lastIndexOf('/') + 1);
            try {
                long seq = Long.parseLong(tail.substring(0, tail.length() - 4));
                byte[] jpeg = jpegSource.apply(seq);
                if (jpeg != null) {
                    ex.getResponseHeaders().set("Content-Type", "image/jpeg");
                    ex.sendResponseHeaders(200, jpeg.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(jpeg);
                    }
                    ex.close();
                    return;
                }
                respond(ex, "{\"error\":\"unknown frame\"}");
                return;
            } catch (NumberFormatException ignore) {
                // fall through to JSON list
            }
        }
        respond(ex, shotsJson());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        // P3-fix(Minor): 优雅关闭 executor，避免线程泄漏
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
    }

    /** Shot metadata as JSON, with the real JPEG byte size (E2). */
    private String shotsJson() {
        List<CameraModel.Shot> list = shotSource.get();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (CameraModel.Shot s : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            byte[] jpeg = jpegSource.apply(s.frameSeq);
            sb.append("{\"frameSeq\":").append(s.frameSeq)
              .append(",\"timeMs\":").append(s.timeMs)
              .append(",\"lat\":").append(s.lat)
              .append(",\"lon\":").append(s.lon)
              .append(",\"altM\":").append(s.altM)
              .append(",\"sizeBytes\":").append(jpeg == null ? 0 : jpeg.length)
              .append(",\"gimbalPitchDeg\":").append(s.gimbalPitchDeg)
              .append(",\"gimbalYawDeg\":").append(s.gimbalYawDeg)
              .append(",\"droneRollDeg\":").append(s.droneRollDeg)
              .append(",\"dronePitchDeg\":").append(s.dronePitchDeg)
              .append(",\"droneYawDeg\":").append(s.droneYawDeg)
              .append(",\"targets\":[");
            boolean tFirst = true;
            for (CameraModel.CapturedTarget t : s.targets) {
                if (!tFirst) {
                    sb.append(',');
                }
                tFirst = false;
                sb.append("{\"id\":").append(t.targetId)
                  .append(",\"kind\":\"").append(t.kind)
                  .append("\",\"u\":").append(t.u)
                  .append(",\"v\":").append(t.v)
                  .append(",\"lat\":").append(t.lat)
                  .append(",\"lon\":").append(t.lon)
                  .append('}');
            }
            sb.append("]}");
        }
        sb.append(']');
        return sb.toString();
    }
}