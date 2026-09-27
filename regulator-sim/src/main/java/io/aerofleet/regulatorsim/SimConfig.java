package io.aerofleet.regulatorsim;

/**
 * regulator-sim 启动配置：由命令行参数解析而来。
 *
 * <pre>
 * java -jar regulator-sim.jar [--port N] [--delay-ms N] [--error-rate F] [--preset-file PATH] [--help]
 *   --port          HTTP 监听端口（默认 18080）
 *   --delay-ms      响应延迟毫秒（默认 0）
 *   --error-rate    随机错误率 0.0-1.0（默认 0.0）
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
    /** 预置数据文件路径（null 表示使用内置默认数据）。 */
    public final String presetFile;

    private SimConfig(int port, int delayMs, double errorRate, String presetFile) {
        this.port = port;
        this.delayMs = delayMs;
        this.errorRate = errorRate;
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
        String presetFile = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help")) {
                RegulatorSimMain.usage();
                System.exit(0);
            } else if (a.equals("--port") && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("[regulator-sim] invalid --port value, using default 18080");
                    port = 18080;
                }
            } else if (a.equals("--delay-ms") && i + 1 < args.length) {
                try {
                    delayMs = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("[regulator-sim] invalid --delay-ms value, using default 0");
                    delayMs = 0;
                }
            } else if (a.equals("--error-rate") && i + 1 < args.length) {
                try {
                    errorRate = Double.parseDouble(args[++i]);
                    if (errorRate < 0.0 || errorRate > 1.0) {
                        System.err.println("[regulator-sim] --error-rate must be 0.0-1.0, using default 0.0");
                        errorRate = 0.0;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[regulator-sim] invalid --error-rate value, using default 0.0");
                    errorRate = 0.0;
                }
            } else if (a.equals("--preset-file") && i + 1 < args.length) {
                presetFile = args[++i];
            } else {
                System.err.println("[regulator-sim] unknown argument: " + a);
                RegulatorSimMain.usage();
                System.exit(1);
            }
        }

        return new SimConfig(port, delayMs, errorRate, presetFile);
    }
}