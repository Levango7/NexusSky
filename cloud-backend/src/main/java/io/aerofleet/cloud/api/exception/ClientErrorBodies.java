package io.aerofleet.cloud.api.exception;

import java.util.Map;

/**
 * 控制器「宽捕」边界（{@code catch (Exception e)}）错误响应体的唯一构造处。
 * <p>
 * 为什么需要它：宽捕会把 IO / Redis / Onvif / SQL 等<b>基础设施异常</b>一并吞进来，
 * 而这些异常的消息含内部 host、URL、SQL 片段、凭据名等；原样拼进响应体等于把
 * 内部拓扑暴露给客户端。{@link ApiExceptionHandler} 对同一问题早有定论
 * （500 一律通用文案，细节只进服务端日志）——控制器宽捕处必须与它一致，
 * 否则全局 advice 的治理会被各个 catch 单点绕开。
 * <p>
 * 口径：
 * <ul>
 *   <li><b>领域异常</b>（{@code IllegalArgumentException} / {@code IllegalStateException} /
 *       自有领域异常，消息由服务端自建）→ 保留自建消息，照常回；</li>
 *   <li><b>宽捕尾巴</b>（{@code catch (Exception e)}）→ 一律通用文案；
 *       完整异常（含堆栈）记服务端日志，定位靠 traceId/grep 日志，不靠客户端文本。</li>
 * </ul>
 * 单测见 {@code ControllerErrorBodyLeakTest}（每个用 canary 串钉住「不泄露」）。
 */
public final class ClientErrorBodies {

    /** 通用文案（与 ApiExceptionHandler 生产模式一致）。 */
    public static final String INTERNAL_SERVER_ERROR = "internal server error";

    private ClientErrorBodies() {
    }

    /**
     * 带上下文的通用错误体：保留「哪一步失败」给调用方定位，
     * 不附带任何内部细节。例：{@code failed to send config (internal server error)}。
     *
     * @param context 服务端自建的操作名（不得含异常文本或请求内容）
     */
    public static Map<String, Object> failed(String context) {
        return Map.of("error", context + " (" + INTERNAL_SERVER_ERROR + ")");
    }

    /** 不带上文的通用错误体。 */
    public static Map<String, Object> internal() {
        return Map.of("error", INTERNAL_SERVER_ERROR);
    }
}
