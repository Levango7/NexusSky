package io.aerofleet.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Virtual drone simulator entry point: a software stand-in for a PX4 flight
 * controller speaking MAVLink v2 over UDP. Supports mission upload (Mission
 * Protocol server role), arm/disarm/takeoff/mission start/RTL commands, and a
 * 20 Hz physics + telemetry tick loop.
 */
public final class DroneSimMain {

    private static final Logger log = LoggerFactory.getLogger(DroneSimMain.class);

    private DroneSimMain() {
    }

    public static void main(String[] args) {
        SimConfig config = SimConfig.parse(args);

        log.info("[sim] AeroFleet virtual drone simulator starting");
        log.info("[sim] config: name={} sysid={} port={} bind={} lat={} lon={} speed={}m/s scenario={}",
                config.name, config.sysid, config.port, config.bindIp, config.lat, config.lon, config.speed, config.scenario);
        if (config.envEnabled) {
            log.info("[sim] env: scenario={} seed={} windMax={}m/s tempRange={}:{}C",
                    config.envScenario, config.envSeed, config.envWindMax, config.envTempRange[0], config.envTempRange[1]);
        } else {
            log.info("[sim] env: disabled (no --env flag)");
        }

        try (VirtualDrone drone = new VirtualDrone(config)) {
            drone.start();
            SimLog.info("listening on UDP " + config.port
                    + " (waiting for GCS packets to learn peer address)");
            log.info("[sim] running. Press Ctrl+C to stop.");

            CountDownLatch stop = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                SimLog.info("shutting down");
                stop.countDown();
            }));
            // Park the main thread; tick/telemetry run on their own threads.
            while (true) {
                if (stop.await(1, TimeUnit.MINUTES)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            SimLog.error("simulator failed to start: " + e.getMessage());
            SimLog.error("hint: is port " + config.port + " already in use? (--port to change)");
            System.exit(1);
        }
    }
}
