package io.aerofleet.sim;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Virtual drone simulator entry point: a software stand-in for a PX4 flight
 * controller speaking MAVLink v2 over UDP. Supports mission upload (Mission
 * Protocol server role), arm/disarm/takeoff/mission start/RTL commands, and a
 * 20 Hz physics + telemetry tick loop.
 */
public final class DroneSimMain {

    private DroneSimMain() {
    }

    public static void main(String[] args) {
        SimConfig config = SimConfig.parse(args);

        System.out.println("[sim] AeroFleet virtual drone simulator starting");
        System.out.println("[sim] config: name=" + config.name + " sysid=" + config.sysid
                + " port=" + config.port + " bind=" + config.bindIp
                + " lat=" + config.lat + " lon=" + config.lon
                + " speed=" + config.speed + "m/s scenario=" + config.scenario);
        // M0b 环境气象配置打印（FR-01/03/05，DFX 4.4 配置可追溯）
        if (config.envEnabled) {
            System.out.println("[sim] env: scenario=" + config.envScenario
                    + " seed=" + config.envSeed
                    + " windMax=" + config.envWindMax + "m/s"
                    + " tempRange=" + config.envTempRange[0] + ":" + config.envTempRange[1] + "C");
        } else {
            System.out.println("[sim] env: disabled (no --env flag)");
        }

        try (VirtualDrone drone = new VirtualDrone(config)) {
            drone.start();
            SimLog.info("listening on UDP " + config.port
                    + " (waiting for GCS packets to learn peer address)");
            System.out.println("[sim] running. Press Ctrl+C to stop.");

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
