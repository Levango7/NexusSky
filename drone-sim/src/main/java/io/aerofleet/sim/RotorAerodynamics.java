package io.aerofleet.sim;

import java.util.List;

/**
 * 旋翼气动模型抽象接口（M4 硬件抽象，FR-07）。
 * <p>
 * 输入旋翼参数 + 飞行状态，输出各旋翼气动遥测（推力/转速/桨效/功耗）+ 总推力/总功耗。
 * {@link SimulatedRotorAerodynamics} 为简化动量理论实现。
 * 未来接真实 CFD/叶素动量理论只需新增实现类。
 */
public interface RotorAerodynamics {

    /**
     * 计算各旋翼气动参数（FR-07/FR-09）。
     *
     * @param config      旋翼气动配置
     * @param flightState 飞行状态
     * @return 各旋翼气动结果 + 总推力/总功耗
     */
    RotorAeroResult compute(RotorConfig config, FlightState flightState);

    /** 气动计算结果（各旋翼 + 汇总）。 */
    record RotorAeroResult(List<RotorResult> rotors, double totalThrust, double totalPower) {
    }

    /** 单个旋翼气动结果（FR-08/数据约束 6.4）。 */
    record RotorResult(int rotorIndex, double rpm, double thrust, double efficiency,
                       double powerConsumption) {
        public RotorResult {
            if (rotorIndex < 0) {
                throw new IllegalArgumentException("rotorIndex must be >= 0, got " + rotorIndex);
            }
            if (rpm < 0 || rpm > 20000) {
                throw new IllegalArgumentException("rpm must be 0-20000, got " + rpm);
            }
            if (thrust < 0) {
                throw new IllegalArgumentException("thrust must be >= 0, got " + thrust);
            }
            if (efficiency < 0 || efficiency > 1) {
                throw new IllegalArgumentException("efficiency must be 0-1, got " + efficiency);
            }
            if (powerConsumption < 0) {
                throw new IllegalArgumentException(
                        "powerConsumption must be >= 0, got " + powerConsumption);
            }
        }
    }

    /** 飞行状态输入（气动模型计算用）。 */
    record FlightState(double totalMassKg, double vz, double groundSpeed,
                        double pitchRad, double rollRad) {
    }
}