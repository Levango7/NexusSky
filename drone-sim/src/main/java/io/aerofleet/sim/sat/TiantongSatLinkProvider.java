package io.aerofleet.sim.sat;

/**
 * 天通卫星链路提供者 — 真实接入预留。
 * <p>
 * 当前为占位实现，所有方法抛出 UnsupportedOperationException。
 * 真实天通卫星接入尚未实现，请使用 {@link SimulatedSatLinkProvider}。
 * <p>
 * 天通卫星是中国自主移动通信卫星系统，S 波段，带宽约 9.6kbps，延迟约 500ms。
 */
public class TiantongSatLinkProvider implements SatLinkProvider.TiantongSatProvider {

    private final String satId;

    /**
     * 构造天通卫星链路提供者占位实现。
     *
     * @param satId 卫星标识
     */
    public TiantongSatLinkProvider(String satId) {
        this.satId = satId;
    }

    @Override
    public boolean connect() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public void disconnect() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getLinkQuality() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getBandwidth() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public long getDelay() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
    }

    @Override
    public double getElevation() {
        throw new UnsupportedOperationException("真实天通卫星接入尚未实现，请使用 SimulatedSatLinkProvider");
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