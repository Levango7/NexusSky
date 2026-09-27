package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;

/**
 * 监管上报通道抽象接口。
 * <p>
 * 定义与监管平台（UOM/USSP/其他）的四项交互契约：
 * 实名状态验证、激活上报、注销、遥测上报。
 * <p>
 * 实现类通过配置项 {@code aerofleet.regulator.sink-type} 切换：
 * <ul>
 *   <li>{@code uom}  — UomReportSink（真实 UOM 平台对接）</li>
 *   <li>{@code sim}  — SimReportSink（对接 regulator-sim 模拟平台）</li>
 *   <li>{@code none} — NoopReportSink（空实现，所有操作返回默认成功）</li>
 * </ul>
 * <p>
 * 所有方法均为同步调用。实现类应自行处理网络超时、重试和异常降级逻辑，
 * 不向调用方抛出未检查异常；失败信息通过返回结果对象的 {@code errorMessage} 字段传达。
 */
public interface RegulatorReportSink {

    /**
     * 实名状态验证（MH/T 3030 交互一）。
     * <p>
     * 向监管平台查询指定无人机的实名登记状态。验证成功时返回脱敏后的
     * 所有者姓名和登记日期；未实名或不存在时返回对应状态枚举；
     * 网络或服务异常时返回 {@link io.aerofleet.cloud.regulator.model.VerifyStatus#ERROR}。
     *
     * @param request 验证请求，包含 sysid、productSerialNo、realNameCertNo
     * @return 验证结果，包含 status、ownerName（脱敏）、registerDate、errorMessage；
     *         正常情况下 errorMessage 为 null，异常时包含错误描述
     */
    VerifyResult verifyRealNameStatus(VerifyRequest request);

    /**
     * 激活上报（MH/T 3030 交互二）。
     * <p>
     * 当无人机实名验证通过后，向监管平台上报激活信息，包括激活时间和
     * 激活位置坐标。激活成功时监管平台分配唯一激活标识符；
     * 失败时 success 为 false 且 errorMessage 包含错误描述。
     *
     * @param request 激活请求，包含 sysid、productSerialNo、activationTime、latitude、longitude
     * @return 激活结果，包含 success、activationId（成功时监管平台分配）、errorMessage；
     *         正常情况下 errorMessage 为 null，异常时包含错误描述
     */
    ActivationResult reportActivation(ActivationRequest request);

    /**
     * 注销上报（MH/T 3030 交互三）。
     * <p>
     * 当无人机停止运营时，向监管平台上报注销信息。注销成功时监管平台
     * 分配唯一注销标识符；失败时 success 为 false 且 errorMessage 包含错误描述。
     *
     * @param request 注销请求，包含 sysid、productSerialNo、cancellationReason
     * @return 注销结果，包含 success、cancellationId（成功时监管平台分配）、errorMessage；
     *         正常情况下 errorMessage 为 null，异常时包含错误描述
     */
    CancellationResult reportCancellation(CancellationRequest request);

    /**
     * 遥测上报（周期性遥测数据上报通道）。
     * <p>
     * 周期性向监管平台上报无人机遥测数据快照，包括位置、高度、速度、
     * 航向和飞行状态。上报成功时 success 为 true；
     * 失败时 success 为 false 且 errorMessage 包含错误描述。
     * <p>
     * 实现类应保证遥测上报不阻塞调用线程过久，建议使用异步队列 +
     * 独立线程池执行实际网络请求。
     *
     * @param report 遥测数据，包含 sysid、productSerialNo、timestamp、latitude、longitude、
     *               altitudeM、groundSpeedMs、headingDeg、flightStatus
     * @return 上报结果，包含 success、errorMessage；
     *         正常情况下 errorMessage 为 null，异常时包含错误描述
     */
    TelemetryResult reportTelemetry(TelemetryReport report);
}