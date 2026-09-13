package io.aerofleet.sim;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Ground-truth HTTP sidecar of the simulator: exposes the synthetic-target
 * world state and the camera-shot metadata for the cloud backend and the
 * GCS. Uses the JDK built-in HttpServer - zero extra dependencies.
 *
 *   GET /targets        -> [{id, kind, lat, lon, speedMps, headingDeg}, ...]
 *   GET /camera/shots   -> [{frameSeq, timeMs, lat, lon, altM, gimbal...,
 *                            targets:[{id,kind,u,v,lat,lon}]}, ...]
 *   GET /health         -> {ok: true, uptimeSec}
 *
 * Bound to loopback by default (a sim-world truth channel, not a public API).
 */
public final class TargetStateServer {

    private final TargetSimulator targets;
    private final long bootMs = System.currentTimeMillis();
    private final Supplier<List<CameraModel.Shot>> shotSource;
    private HttpServer server;
    private final int port;

    public TargetStateServer(TargetSimulator targets, int port,
                             Supplier<List<CameraModel.Shot>> shotSource) {
        this.targets = targets;
        this.port = port;
        this.shotSource = shotSource;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/targets", ex -> respond(ex, targets.snapshotJson()));
        server.createContext("/camera/shots", ex -> respond(ex, shotsJson()));
        server.createContext("/health", ex -> respond(ex,
                "{\"ok\":true,\"uptimeSec\":" + (System.currentTimeMillis() - bootMs) / 1000 + "}"));
        server.start();
        SimLog.info("ground-truth HTTP on 127.0.0.1:" + port
                + " (/targets /camera/shots /health)");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
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

    /** Shot metadata as JSON (the "photos" of the imaging chain). */
    private String shotsJson() {
        List<CameraModel.Shot> list = shotSource.get();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (CameraModel.Shot s : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"frameSeq\":").append(s.frameSeq)
              .append(",\"timeMs\":").append(s.timeMs)
              .append(",\"lat\":").append(s.lat)
              .append(",\"lon\":").append(s.lon)
              .append(",\"altM\":").append(s.altM)
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
