package io.aerofleet.sim;

/**
 * Minimal ASCII-only console logger with [sim] prefix.
 * Keeps output safe for Windows GBK consoles (no localized characters).
 */
public final class SimLog {

    private SimLog() {
    }

    private static String ts() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    public static void info(String msg) {
        System.out.println("[sim] " + ts() + " INFO  " + msg);
    }

    public static void warn(String msg) {
        System.out.println("[sim] " + ts() + " WARN  " + msg);
    }

    public static void error(String msg) {
        System.out.println("[sim] " + ts() + " ERROR " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.out.println("[sim] " + ts() + " ERROR " + msg + ": " + t);
    }
}
