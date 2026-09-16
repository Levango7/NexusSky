package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟旋翼气动模型（M4 硬件抽象，FR-09/FR-11）。
 * <p>
 * 基于简化动量理论计算各旋翼气动参数：
 * <ul>
 *   <li>推力 T = CT × ρ × n² × D⁴（CT 推力系数，ρ 空气密度，n 转速 rps，D 桨叶直径）</li>
 *   <li>诱导功率 P_ind = T^(3/2) / sqrt(2 × ρ × A)（A 桨盘面积）</li>
 *   <li>轴功率 P_total = P_ind / 0.8（含损耗，η_total ≈ 0.8）</li>
 *   <li>桨效 η = P_ind / P_total</li>
 * </ul>
 * 悬停所需总推力 = 总质量 × G + 爬升附加（质量 × vz）。
 */
public class SimulatedRotorAerodynamics implements RotorAerodynamics {

    private static final double G = 9.81;
    /** 推力系数（典型值，简化模型）。 */
    private static final double CT = 0.1;
    /** 总效率（含电机/ESC/传动损耗，典型 0.8）。 */
    private static final double ETA_TOTAL = 0.8;

    @Override
    public RotorAeroResult compute(RotorConfig config, FlightState state) {
        int n = config.rotorCount();
        double rho = config.airDensity();
        double D = config.diameter();
        double A = Math.PI * (D / 2.0) * (D / 2.0);  // 桨盘面积

        // 悬停所需总推力 = 总重力 + 爬升附加（FR-09）
        double totalThrustRequired = state.totalMassKg() * G + state.totalMassKg() * state.vz();
        if (totalThrustRequired < 0) {
            totalThrustRequired = 0;  // 下降时不需负推力
        }
        double thrustPerRotor = totalThrustRequired / n;

        List<RotorResult> rotors = new ArrayList<>();
        double totalPower = 0;
        // M4 代码审查 #4：rpm 被 cap 后实际能产生的推力会小于所需推力，
        // 需用 cap 后的 rpm 重新计算实际推力，否则上报的推力与转速不一致。
        double totalActualThrust = 0;
        for (int i = 0; i < n; i++) {
            // FR-09 简化动量理论：T = CT × ρ × n² × D⁴ → n = sqrt(T / (CT × ρ × D⁴))
            double denom = CT * rho * Math.pow(D, 4);
            double rps = denom > 0 ? Math.sqrt(thrustPerRotor / denom) : 0;
            double rpm = rps * 60;

            // 限制在配置的转速上限内
            boolean rpmCapped = false;
            if (rpm > config.maxRpm()) {
                rpm = config.maxRpm();
                rpmCapped = true;
            }

            // M4 代码审查 #4：rpm 被 cap 后用 cap 后的转速重算实际推力
            // T_actual = CT × ρ × (rpm/60)² × D⁴
            double actualThrust = thrustPerRotor;
            if (rpmCapped) {
                double cappedRps = rpm / 60.0;
                actualThrust = denom * cappedRps * cappedRps;
            }
            totalActualThrust += actualThrust;

            // 诱导功率 P_ind = T^(3/2) / sqrt(2 × ρ × A)
            double pInd = A > 0 && rho > 0
                    ? Math.pow(actualThrust, 1.5) / Math.sqrt(2 * rho * A) : 0;
            // 轴功率（含损耗）
            double pTotal = pInd / ETA_TOTAL;
            // 桨效 η = P_ind / P_total
            double eta = pTotal > 0 ? pInd / pTotal : 0;

            rotors.add(new RotorResult(i, rpm, actualThrust, eta, pTotal));
            totalPower += pTotal;
        }
        return new RotorAeroResult(rotors, totalActualThrust, totalPower);
    }
}