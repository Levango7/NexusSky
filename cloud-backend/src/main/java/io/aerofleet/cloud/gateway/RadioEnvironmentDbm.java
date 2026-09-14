package io.aerofleet.cloud.gateway;

/**
 * SiK raw RSSI (approx 2x dB, per RADIO_STATUS spec) -> dBm. Mirrors
 * drone-sim's RadioEnvironment.toSikUnits; kept separate so the backend
 * has no sim dependency.
 */
final class RadioEnvironmentDbm {

    private RadioEnvironmentDbm() {
    }

    /** raw = 2*(100+dbm) clamped to [0,254]; inverse: dbm = raw/2 - 100. */
    static double fromSik(int raw) {
        return raw / 2.0 - 100.0;
    }
}
