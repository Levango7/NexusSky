package io.aerofleet.sim.sat;

/**
 * 卫星链路提供者抽象接口（FR-20 真实卫星接入预留）。
 * <p>
 * 为三类真实卫星体制预留统一接入点：
 * <ul>
 *   <li>天通卫星（S 波段）— {@link TiantongSatProvider}</li>
 *   <li>铱星（L 波段）— {@link IridiumSatProvider}</li>
 *   <li>星链（Ku/Ka 波段）— {@link StarlinkSatProvider}</li>
 * </ul>
 * 仿真实现为 {@link SimulatedSatLinkProvider}，真实实现为各体制的 provider。
 * <p>
 * 所有链路指标方法返回的是当前时刻的瞬时值，由实现方负责更新。
 */
public interface SatLinkProvider {

    /** 卫星体制类型。 */
    enum SatType {
        /** 天通卫星（S 波段）。 */
        TIANTONG("天通", "S"),
        /** 铱星（L 波段）。 */
        IRIDIUM("铱星", "L"),
        /** 星链（Ku/Ka 波段）。 */
        STARLINK("星链", "Ku/Ka");

        private final String label;
        private final String band;

        SatType(String label, String band) {
            this.label = label;
            this.band = band;
        }

        public String label() {
            return label;
        }

        public String band() {
            return band;
        }
    }

    /**
     * 建立卫星链路连接。
     *
     * @return true 若连接成功
     */
    boolean connect();

    /**
     * 断开卫星链路连接。
     */
    void disconnect();

    /**
     * 获取当前链路质量评分（0-1，1 为最优）。
     * <p>
     * 综合仰角、信号强度、误码率等因素。
     *
     * @return 链路质量评分 [0, 1]
     */
    double getLinkQuality();

    /**
     * 获取当前可用带宽（bps）。
     *
     * @return 带宽（bps）
     */
    long getBandwidth();

    /**
     * 获取当前链路延迟（ms）。
     *
     * @return 延迟（ms）
     */
    long getDelay();

    /**
     * 获取当前卫星仰角（度）。
     *
     * @return 仰角 [0, 90]
     */
    double getElevation();

    /**
     * 获取卫星体制类型。
     *
     * @return 卫星体制
     */
    SatType getSatType();

    /**
     * 获取卫星标识（如卫星编号或名称）。
     *
     * @return 卫星标识
     */
    String getSatId();

    /**
     * 是否已连接。
     *
     * @return true 若链路已建立
     */
    boolean isConnected();

    // ===== 子接口：各体制 provider =====

    /**
     * 天通卫星链路提供者（S 波段）。
     * <p>
     * 天通卫星是中国自主移动通信卫星系统，S 波段，带宽约 9.6kbps，延迟约 500ms。
     */
    interface TiantongSatProvider extends SatLinkProvider {
        @Override
        default SatType getSatType() {
            return SatType.TIANTONG;
        }
    }

    /**
     * 铱星卫星链路提供者（L 波段）。
     * <p>
     * 铱星是低轨全球卫星通信系统，L 波段，带宽约 2.4kbps，延迟约 1500ms。
     */
    interface IridiumSatProvider extends SatLinkProvider {
        @Override
        default SatType getSatType() {
            return SatType.IRIDIUM;
        }
    }

    /**
     * 星链卫星链路提供者（Ku/Ka 波段）。
     * <p>
     * 星链是 SpaceX 低轨宽带卫星系统，Ku/Ka 波段，带宽约 100Mbps，延迟约 20ms。
     */
    interface StarlinkSatProvider extends SatLinkProvider {
        @Override
        default SatType getSatType() {
            return SatType.STARLINK;
        }
    }
}