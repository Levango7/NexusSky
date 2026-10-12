package io.aerofleet.regulatorsim;

/**
 * regulator-sim 启动配置：由命令行参数解析而来。
 *
 * <pre>
 * java -jar regulator-sim.jar [--port N] [--delay-ms N] [--error-rate F] [--error-status N] [--preset-file PATH] [--help]
 *   --port          HTTP 监听端口（默认 18080）
 *   --delay-ms      响应延迟毫秒（默认 0）
 *   --error-rate    随机错误率 0.0-1.0（默认 0.0）
 *   --error-status  故障注入返回的 HTTP 状态码（默认 500；设 4xx 可验"语义终态不重试"）
 *   --preset-file   预置数据文件路径（默认内置）
 *   --help          打印帮助
 * </pre>
 */
public final class SimConfig {

    /** HTTP 监听端口。 */
    public final int port;
    /** 响应延迟毫秒数。 */
    public final int delayMs;
    /** 随机错误率（0.0-1.0）。 */
    public final double errorRate;
    /** 故障注入返回的状态码。 */
    public final int errorStatus;
    /** 预置数据文件路径（null 表示使用内置默认数据）。 */
    public final String presetFile;

    private SimConfig(int port, int delayMs, double errorRate, int errorStatus, String presetFile) {
        this.port = port;
        this.delayMs = delayMs;
        this.errorRate = errorRate;
        this.errorStatus = errorStatus;
        this.presetFile = presetFile;
    }

    /**
     * 解析命令行参数，返回 SimConfig 实例。
     * <p>
     * 遇到 {@code --help} 时打印帮助并调用 {@code System.exit(0)}。
     * 参数缺失或格式错误时打印警告并回退默认值。
     *
     * @param args 原始命令行参数数组
     * @return 解析后的配置实例
     */
    public static SimConfig parse(String[] args) {
        int port = 18080;
        int delayMs = 0;
        double errorRate = 0.0;
        int errorStatus = 500;
        String presetFile = null;

        // 两种写法都认：`--port 18080` 与 `--port=18080`（drone-sim 的 SimConfig 同款口径）。
        // 未知参数只警告不退出：System.exit 会让调用方（单测/嵌入方）整个进程被杀，
        // 表现是"surefire fork 猝死"而不是一条可读的失败。
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                RegulatorSimMain.usage();
                System.exit(0);
            }

            String key = a;
            String inlineValue = null;
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 2) {
                key = a.substring(0, eq);
                inlineValue = a.substring(eq + 1);
            }

            // 值要么跟着写（空格分隔），要么内联（= 号）；都没有则该参数缺值
            String value = inlineValue;
            if (value == null && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }

            switch (key) {
                case "--port" -> {
                    try {
                        port = Integer.parseInt(value);
                    } catch (RuntimeException e) {
                        warnInvalid("--port", value, "18080");
                        port = 18080;
                    }
                }
                case "--delay-ms" -> {
                    try {
                        delayMs = Integer.parseInt(value);
                    } catch (RuntimeException e) {
                        warnInvalid("--delay-ms", value, "0");
                        delayMs = 0;
                    }
                }
                case "--error-rate" -> {
                    try {
                        errorRate = Double.parseDouble(value);
                        if (errorRate < 0.0 || errorRate > 1.0) {
                            System.err.println("[regulator-sim] --error-rate must be 0.0-1.0, using default 0.0");
                            errorRate = 0.0;
                        }
                    } catch (RuntimeException e) {
                        warnInvalid("--error-rate", value, "0.0");
                        errorRate = 0.0;
                    }
                }
                case "--error-status" -> {
                    try {
                        errorStatus = Integer.parseInt(value);
                        if (errorStatus < 100 || errorStatus > 599) {
                            System.err.println("[regulator-sim] --error-status must be 100-599, using default 500");
                            errorStatus = 500;
                        }
                    } catch (RuntimeException e) {
                        warnInvalid("--error-status", value, "500");
                        errorStatus = 500;
                    }
                }
                case "--preset-file" -> presetFile = value;
                default -> System.err.println("[regulator-sim] ignoring unknown argument: " + a);
            }
        }

        return new SimConfig(port, delayMs, errorRate, errorStatus, presetFile);
    }

    private static void warnInvalid(String key, String value, String fallback) {
        System.err.println("[regulator-sim] invalid " + key + " value: " + value + ", using default " + fallback);
    }
}