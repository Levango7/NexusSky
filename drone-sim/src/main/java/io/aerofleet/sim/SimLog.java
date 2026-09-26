package io.aerofleet.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimLog {

    private static final Logger log = LoggerFactory.getLogger("sim");

    private SimLog() {
    }

    public static void info(String msg) {
        log.info(msg);
    }

    public static void warn(String msg) {
        log.warn(msg);
    }

    public static void error(String msg) {
        log.error(msg);
    }

    public static void error(String msg, Throwable t) {
        log.error(msg, t);
    }
}

