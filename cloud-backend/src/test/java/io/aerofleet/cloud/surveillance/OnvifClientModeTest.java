package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.surveillance.onvif.OnvifSettings;
import io.aerofleet.cloud.surveillance.onvif.OnvifUnsupportedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifClient} 的模式分派：默认模拟（行为不变）、live 下事件面不假装成功。
 */
@DisplayName("OnvifClient simulated/live 模式")
class OnvifClientModeTest {

    private static OnvifClient liveClient() {
        OnvifSettings settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        return new OnvifClient(settings);
    }

    @Test
    @DisplayName("默认构造 = simulated，事件订阅仍走内存回调（既有行为不变）")
    void defaultConstructor_isSimulated() {
        OnvifClient client = new OnvifClient();

        assertThat(client.isLive()).isFalse();
        AtomicReference<String> received = new AtomicReference<>();
        String handle = client.subscribeEvents("192.168.1.10", 80, "admin", "p", received::set);
        assertThat(client.simulateEvent(handle, "<Event/>")).isTrue();
        assertThat(received.get()).isEqualTo("<Event/>");
    }

    @Test
    @DisplayName("live：事件订阅抛 OnvifUnsupportedException，不返回假句柄")
    void liveMode_subscribeEvents_throws() {
        OnvifClient client = liveClient();

        assertThat(client.isLive()).isTrue();
        assertThatThrownBy(() -> client.subscribeEvents("127.0.0.1", 80, "admin", "p", payload -> {
        }))
                .isInstanceOf(OnvifUnsupportedException.class)
                .hasMessageContaining("refusing to fake");
        assertThat(client.activeSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("live：unsubscribe 幂等（SSE 关闭路径不会二次报错）")
    void liveMode_unsubscribeIsNoOp() {
        OnvifClient client = liveClient();

        client.unsubscribeEvents("127.0.0.1:80");
        client.unsubscribeEvents(null);

        assertThat(client.activeSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("live：参数校验发生在任何网络调用之前（offline 可断言）")
    void liveMode_validatesBeforeNetwork() {
        OnvifClient client = liveClient();

        assertThatThrownBy(() -> client.getRtspUrl("", 80, "admin", "p", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ip must not be blank");
        assertThatThrownBy(() -> client.getDeviceCapabilities("127.0.0.1", 80, "admin", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass must not be empty");
        assertThatThrownBy(() -> client.ptzControl("127.0.0.1", 80, "admin", "p", "panLeft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported ptz command");
    }
}
