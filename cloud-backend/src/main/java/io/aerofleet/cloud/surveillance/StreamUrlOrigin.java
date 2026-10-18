package io.aerofleet.cloud.surveillance;

/**
 * 流地址的**来源**与可信度。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>取流地址这件事上，代码库里一直有两种做法，而它们的性质完全不同，
 * 此前却没有被区分开：
 *
 * <ul>
 *   <li>{@code Gb28181Adapter} 返回 {@code gb28181-invite://}，并在日志里写明
 *       "no fake playable URL returned" —— 这是**描述性标识**，媒体面未实现，
 *       根本产不出可播地址；</li>
 *   <li>三个私有协议适配器与 ONVIF 按<b>厂商公开的 RTSP URL 规范</b>拼一个
 *       {@code rtsp://} 地址 —— 这个格式对真设备是<b>可能正确</b>的，但代码
 *       从未向设备确认过（都挂着 {@code TODO}）。</li>
 * </ul>
 *
 * <p>把后一类也说成"可播"，是把"也许能用"讲成了"能用"；把它说成"不可用"
 * 又是错杀（真海康摄像头在 554 上那个格式确实能播）。所以这里不造"支持/不支持"
 * 这个布尔，而是如实说明**这个地址是怎么来的、有没有被设备确认过**，
 * 由消费方自己决定怎么表述。
 */
public enum StreamUrlOrigin {

    /**
     * 按厂商公开的 RTSP URL 规范推导。
     *
     * <p>格式对真实设备可能正确，但<b>没有向设备校验过</b>：端口、路径、
     * 鉴权方式（basic 还是 digest）都可能与实际不符。因此 {@link #playable}
     * 为 {@code true} 而 {@link #deviceVerified} 为 {@code false} ——
     * "可能可播，但未经验证"，这两件事必须同时说出来。
     */
    VENDOR_STANDARD_FORMAT(true, false),

    /**
     * 由 ONVIF {@code Media} 服务的 {@code GetStreamUri} 向设备实际取回。
     *
     * <p>这是唯一"设备确认过"的情形。接入真实 SOAP 调用后应改用本枚举值。
     */
    ONVIF_GETSTREAM_URI(true, true),

    /**
     * 描述性标识，**不可播**。
     *
     * <p>媒体面未实现时用。返回它比返回一个看起来能播的假地址更诚实：
     * 前端能明确告诉使用者"这个通道现在放不出画面"，而不是给一个
     * 连上去必然失败的地址再配一个语焉不详的报错。
     */
    DESCRIPTIVE_ONLY(false, false);

    private final boolean playable;
    private final boolean deviceVerified;

    StreamUrlOrigin(boolean playable, boolean deviceVerified) {
        this.playable = playable;
        this.deviceVerified = deviceVerified;
    }

    /** 该地址是否<b>预期</b>可直接播放（不等于已验证可用）。 */
    public boolean playable() {
        return playable;
    }

    /** 该地址是否真的向设备取回过。 */
    public boolean deviceVerified() {
        return deviceVerified;
    }
}