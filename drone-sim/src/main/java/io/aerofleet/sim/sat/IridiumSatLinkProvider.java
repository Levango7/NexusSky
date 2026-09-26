package io.aerofleet.sim.sat;

/**
 * 铱星卫星链路提供者 — 真实接入预留。
 * <p>
 * 当前为占位实现，所有方法抛出 UnsupportedOperationException。
 * 真实铱星接入尚未实现，请使用 {@link SimulatedSatLinkProvider}。
 * <p>
 * 铱星是低轨全球卫星通信系统，L 波段，带宽约 2.4kbps，延迟约 1500ms。
 */
public class IridiumSatLinkProvider implements SatLinkProvider.IridiumSatProvider {

    private final String satId;

    /**
     * 构造铱星卫星链路提供者占位实现。
     *
     * @param satId 卫星标识
     */
    public IridiumSatLinkProvider(String satId) {
        this.satId = satId;
    }

    @Override
    public boolean connect() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public void disconnect() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getLinkQuality() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getBandwidth() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getDelay() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getElevation() {
        throw new UnsupportedOperationException("真实铱星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public String getSatId() {
        return satId;
    }

    @Override
    public boolean isConnected() {
        return false;
    }
}