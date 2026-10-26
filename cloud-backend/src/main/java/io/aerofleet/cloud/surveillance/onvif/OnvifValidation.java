package io.aerofleet.cloud.surveillance.onvif;

/**
 * 参数校验（模拟后端与 live 后端共用）。
 *
 * <p>这些消息是对外契约：{@code OnvifClientTest} 逐条断言过
 * （{@code "ip must not be blank"} 等）。live 后端不能另写一套措辞，
 * 否则同一个非法入参在两种模式下会得到不同的错误文本。
 */
public final class OnvifValidation {

    private OnvifValidation() {
    }

    public static void requireConnectionArgs(String ip, int port, String user, String pass) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
        if (pass == null || pass.isEmpty()) {
            throw new IllegalArgumentException("pass must not be empty");
        }
    }

    public static void requireChannel(int channel) {
        if (channel < 1) {
            throw new IllegalArgumentException("channel must be >= 1");
        }
    }

    public static void requireSubnet(String subnet) {
        if (subnet == null || subnet.isBlank()) {
            throw new IllegalArgumentException("subnet must not be blank");
        }
    }

    public static void requirePtzCommand(String cmd) {
        if (cmd == null || !OnvifPtzCommands.ALL.contains(cmd)) {
            throw new IllegalArgumentException(
                    "unsupported ptz command: " + cmd + ", supported: " + OnvifPtzCommands.ALL);
        }
    }
}
