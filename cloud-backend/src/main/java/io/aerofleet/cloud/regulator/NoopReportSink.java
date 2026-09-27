package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 监管上报通道空实现（Noop）。
 * <p>
 * 默认实现：配置项 {@code aerofleet.regulator.sink-type} 缺失或为 {@code none} 时激活
 * （显式配置 {@code sim}/{@code uom} 时由对应实现接管）。
 * 所有操作均返回默认成功结果，不发起任何网络调用，
 * 仅在 DEBUG 级别输出日志，适用于开发/测试环境。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.regulator.sink-type", havingValue = "none", matchIfMissing = true)
public class NoopReportSink implements RegulatorReportSink {

    private static final Logger log = LoggerFactory.getLogger(NoopReportSink.class);

    @Override
    public VerifyResult verifyRealNameStatus(VerifyRequest request) {
        log.debug("Noop verifyRealNameStatus: sysid={}, productSerialNo={}",
                request.sysid(), request.productSerialNo());
        return new VerifyResult(VerifyStatus.VERIFIED, "", "", null);
    }

    @Override
    public ActivationResult reportActivation(ActivationRequest request) {
        log.debug("Noop reportActivation: sysid={}, productSerialNo={}",
                request.sysid(), request.productSerialNo());
        return new ActivationResult(true, "NOOP", null);
    }

    @Override
    public CancellationResult reportCancellation(CancellationRequest request) {
        log.debug("Noop reportCancellation: sysid={}, productSerialNo={}",
                request.sysid(), request.productSerialNo());
        return new CancellationResult(true, "NOOP", null);
    }

    @Override
    public TelemetryResult reportTelemetry(TelemetryReport report) {
        log.debug("Noop reportTelemetry: sysid={}, productSerialNo={}",
                report.sysid(), report.productSerialNo());
        return new TelemetryResult(true, null);
    }
}