package io.aerofleet.sim;

/**
 * 真实数据源占位（FR-29 M0b 边界）。
 * <p>
 * M0b 边界：不实现真实气象站/API 接入。
 * 未来接入时替换此实现，不改架构（{@link EnvironmentSource} 接口不变）。
 * 预留接入点：气象站 SDK / HTTP API / 消息队列。
 */
public final class RealEnvSource implements EnvironmentSource {

    @Override
    public EnvironmentState snapshot() {
        throw new UnsupportedOperationException("RealEnvSource not implemented in M0b");
    }

    @Override
    public void evolve(double dt) {
        throw new UnsupportedOperationException("RealEnvSource not implemented in M0b");
    }
}