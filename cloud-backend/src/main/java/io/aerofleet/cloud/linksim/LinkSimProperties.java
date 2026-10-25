package io.aerofleet.cloud.linksim;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * link-sim 运行时控制通道的云端转发配置（P2 #20，aerofleet.linksim.*）。
 *
 * <p>link-sim 是独立的 CLI 进程，控制通道默认关闭（{@code --control-port 0}）。
 * 云端只做透明转发：本机 link-sim 必须以 {@code --control-port 14601} 启动，
 * 否则本组端点返回 503（连不上），不会伪造成功。
 */
@Component
@ConfigurationProperties(prefix = "aerofleet.linksim")
public class LinkSimProperties {

    /**
     * link-sim 控制面完整地址（含 {@code /profile}）。
     *
     * <p>只允许回环地址：link-sim 的控制通道本身不做应用层鉴权，安全边界
     * 就是"只绑 127.0.0.1"，云端不应把它代理到别的主机。
     */
    private String controlUrl = "http://127.0.0.1:14601/profile";

    /** 转发超时（ms）：连接 + 读取共用；超时映射 504。 */
    private int timeoutMs = 3000;

    public String getControlUrl() {
        return controlUrl;
    }

    public void setControlUrl(String controlUrl) {
        this.controlUrl = controlUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
