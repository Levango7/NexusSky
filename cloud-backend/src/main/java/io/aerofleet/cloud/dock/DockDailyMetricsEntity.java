package io.aerofleet.cloud.dock;

import jakarta.persistence.*;

/** 日结度量（spec R5：日结聚合幂等重算，不从内存遥测现算）。 */
@Entity
@Table(name = "dock_metrics_daily")
public class DockDailyMetricsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "dock_id", nullable = false)
    public Long dockId;

    /** yyyy-MM-dd（本地时区）。 */
    @Column(name = "day_date", nullable = false, length = 10)
    public String day;

    @Column(nullable = false)
    public int sorties;

    @Column(name = "flight_minutes", nullable = false)
    public double flightMinutes;

    @Column(name = "door_cycles", nullable = false)
    public int doorCycles;

    @Column(name = "online_seconds", nullable = false)
    public long onlineSeconds;

    @Column(name = "fault_count", nullable = false)
    public int faultCount;

    @Column(name = "temp_excursions", nullable = false)
    public int tempExcursions;

    @Column(name = "battery_swaps", nullable = false)
    public int batterySwaps;

    @Column(name = "avg_charge_time_min")
    public Double avgChargeTimeMin;
}
