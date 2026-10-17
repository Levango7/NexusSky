package io.aerofleet.cloud.surveillance;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 设备能力（capability）的**唯一口径**：归一化词汇表 + 三态判定。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>能力声明一直存在（{@link SurveillanceDevice#getCapabilities()}，已持久化、已随
 * {@code deviceView} 返回给前端），但<strong>几乎没有调用点真的据此改变行为</strong>：
 * 唯一一处是 {@code AirGroundCoordinationService} 里的 {@code contains("PTZ")}，
 * 而 {@code SurveillanceController} 的 PTZ / 取流端点不看能力就照常执行。
 * 于是同一台设备，空地协同服务认为它不能转，PTZ 端点却照单全收。
 *
 * <p>更麻烦的是<strong>各适配器声明的词汇表并不一致</strong>（实测）：
 *
 * <pre>
 *   海康/大华/宇视 : Device, Media, Events, PTZ, (Imaging)
 *   ONVIF         : Device, Media, Events, PTZ, Imaging  —— 且取决于端口号
 *   GB28181 相机   : PTZ, STREAM, RECORD                  —— 用 STREAM，不是 Media
 *   GB28181 报警设备: ALARM
 * </pre>
 *
 * <p>所以任何直接按字面名做门控的代码，都会把 GB28181 相机的取流误判成
 * 「不支持」（它写的是 {@code STREAM}）。本类把别名折叠成规范名
 * （{@code STREAM→MEDIA}、{@code RECORD→RECORDING}、大小写无关），
 * 让门控只认规范名。
 *
 * <h2>三态而不是布尔</h2>
 *
 * <p>「设备没声明任何能力」是<b>未知</b>，不是<b>不支持</b>。两种策略各有正当场景：
 *
 * <ul>
 *   <li><b>自动选设备</b>（如挑选最近的 PTZ 设备）应<b>保守</b>：未声明就不选，
 *       否则可能把一台根本不能转的摄像头当成候选；</li>
 *   <li><b>运维手动操作</b>（运维在界面上按了 PTZ）应<b>放行</b>：未声明就拒绝，
 *       会让大量早期注册、没写能力的设备平白失去这个功能 —— 那是功能减法，
 *       而不是诚实。</li>
 * </ul>
 *
 * <p>因此本类返回 {@link Support} 三态，<b>由调用点显式声明</b>对 UNKNOWN 的策略，
 * 而不是把某个默认值偷偷固化进判定里。
 */
public final class DeviceCapability {

    /** 设备信息服务（设备信息、状态、时间同步）。 */
    public static final String DEVICE = "DEVICE";
    /** 媒体/取流服务。GB28181 的 {@code STREAM} 折叠到这里。 */
    public static final String MEDIA = "MEDIA";
    /** 事件订阅服务。 */
    public static final String EVENTS = "EVENTS";
    /** 云台控制。 */
    public static final String PTZ = "PTZ";
    /** 图像参数（亮度/对比度/变焦等）。 */
    public static final String IMAGING = "IMAGING";
    /** 录像服务。GB28181 的 {@code RECORD} 折叠到这里。 */
    public static final String RECORDING = "RECORDING";
    /** 报警输入输出。GB28181 报警设备只有这一项。 */
    public static final String ALARM = "ALARM";

    /** 能力判定结果：区分「明确不支持」与「没说」。 */
    public enum Support {
        /** 设备明确声明支持。 */
        YES,
        /** 设备明确声明不支持（声明了能力集，但里面没有这一项）。 */
        NO,
        /** 设备根本没声明任何能力 —— 不知道，不等于不支持。 */
        UNKNOWN
    }

    private DeviceCapability() {
    }

    /**
     * 把各适配器的原始声明归一化成规范名集合。
     *
     * <p>大小写无关、忽略首尾空白；已知别名折叠到规范名（{@code STREAM→MEDIA}、
     * {@code RECORD→RECORDING}）。未知名字<b>原样保留为大写</b>，不静默丢弃 ——
     * 丢掉了就再也看不出某设备声明过什么。
     *
     * @param raw 原始声明（可为 null / 空）
     * @return 规范名集合（不可变，永不为 null）
     */
    public static Set<String> normalize(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return Collections.unmodifiableSet(out);
        }
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String key = s.trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            switch (key) {
                case "STREAM" -> out.add(MEDIA);
                case "RECORD" -> out.add(RECORDING);
                default -> out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * 判定设备是否支持某项能力。
     *
     * @param raw       原始能力声明
     * @param canonical 规范能力名（{@link #PTZ} 等）
     * @return 三态结果；{@code raw} 为空集时返回 {@link Support#UNKNOWN}
     */
    public static Support supportOf(Collection<String> raw, String canonical) {
        Set<String> normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return Support.UNKNOWN;
        }
        return normalized.contains(canonical) ? Support.YES : Support.NO;
    }

    /**
     * 便捷判定：只区分「明确不支持」。未声明（UNKNOWN）视为不阻断。
     *
     * <p>用于<b>运维手动操作</b>的门控（PTZ 端点、取流端点）—— 详见类注释。
     */
    public static boolean notExplicitlyUnsupported(Collection<String> raw, String canonical) {
        return supportOf(raw, canonical) != Support.NO;
    }

    /**
     * 便捷判定：只有<b>明确声明支持</b>才算支持。
     *
     * <p>用于<b>自动选设备</b>的门控（如挑选最近的 PTZ 设备）—— 详见类注释。
     */
    public static boolean explicitlySupported(Collection<String> raw, String canonical) {
        return supportOf(raw, canonical) == Support.YES;
    }
}