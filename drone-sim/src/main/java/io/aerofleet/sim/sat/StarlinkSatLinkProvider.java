package io.aerofleet.sim.sat;

/**
 * 星链卫星链路提供者 — 真实接入预留。
 * <p>
 * 当前为占位实现，所有方法抛出 UnsupportedOperationException。
 * 真实星链接入尚未实现，请使用 {@link SimulatedSatLinkProvider}。
 * <p>
 * 星链是 SpaceX 低轨宽带卫星系统，Ku/Ka 波段，带宽约 100Mbps，延迟约 20ms。
 */
public class StarlinkSatLinkProvider implements SatLinkProvider.StarlinkSatProvider {

    private final String satId;

    /**
     * 构造星链卫星链路提供者占位实现。
     *
     * @param satId 卫星标识
     */
    public StarlinkSatLinkProvider(String satId) {
        this.satId = satId;
    }

    @Override
    public boolean connect() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public void disconnect() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getLinkQuality() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getBandwidth() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getDelay() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getElevation() {
        throw new UnsupportedOperationException("真实星链接入尚未实现，请使用 SimulatedSatLinkProvider");
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