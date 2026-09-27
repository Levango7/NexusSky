package io.aerofleet.regulatorsim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * regulator-sim 启动入口：模拟 UOM 监管平台 HTTP API 的独立进程。
 *
 * <pre>
 * java -jar regulator-sim.jar [--port N] [--delay-ms N] [--error-rate F] [--preset-file PATH] [--help]
 *   --port          HTTP 监听端口（默认 18080）
 *   --delay-ms      响应延迟毫秒（默认 0）
 *   --error-rate    随机错误率 0.0-1.0（默认 0.0）
 *   --preset-file   预置数据文件路径（默认内置）
 *   --help          打印帮助
 * </pre>
 *
 * 参照 {@code link-sim} 的 {@code LinkSimMain} 风格：main 方法 + 命令行参数解析 + usage 帮助。
 */
public final class RegulatorSimMain {

    private static final Logger log = LoggerFactory.getLogger(RegulatorSimMain.class);

    public static void main(String[] args) throws Exception {
        SimConfig config = SimConfig.parse(args);

        log.info("[regulator-sim] AeroFleet regulator simulator");
        log.info("[regulator-sim] port={} delay-ms={} error-rate={} preset-file={}",
                config.port,
                config.delayMs,
                config.errorRate,
                config.presetFile != null ? config.presetFile : "(builtin)");

        MockUomServer server = new MockUomServer(config);
        server.start();
        log.info("[regulator-sim] running (Ctrl+C to stop)");

        // 注册 shutdown hook，确保 Ctrl+C 时优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[regulator-sim] shutdown signal received");
            server.stop();
        }));

        // 保持进程运行
        Thread.currentThread().join();
    }

    /** 打印命令行帮助信息。 */
    static void usage() {
        log.info("[regulator-sim] usage: regulator-sim [--port N] [--delay-ms N] [--error-rate F] [--preset-file PATH] [--help]");
        log.info("[regulator-sim]   --port          HTTP listen port (default 18080)");
        log.info("[regulator-sim]   --delay-ms      response delay in ms (default 0)");
        log.info("[regulator-sim]   --error-rate    random error rate 0.0-1.0 (default 0.0)");
        log.info("[regulator-sim]   --preset-file   preset data file path (default builtin)");
        log.info("[regulator-sim]   --help          print this help");
    }
}