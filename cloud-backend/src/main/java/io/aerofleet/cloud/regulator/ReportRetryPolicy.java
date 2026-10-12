package io.aerofleet.cloud.regulator;

/**
 * 监管平台上报的 HTTP 重试判定。
 *
 * <p><b>为什么单独抽一个类</b>：{@link SimReportSink} 与 {@link UomReportSink}
 * 各自实现了一份"失败就重试"，曾把 4xx 也当瞬时故障退避重试。真实平台的 4xx 是
 * <b>语义终态</b>——签名过期、未实名、已注销、拒绝重复激活，重试既救不回来，
 * 又会让平台看到同一个请求被打 3 遍（1s/2s/4s 退避共 7 秒），
 * 严重时被判定为滥用触发限流。两处各写一份判定，下次还会各自漂移。
 *
 * <p><b>判定口径</b>：
 * <ul>
 *   <li>{@code true} = 终态，不该重试</li>
 *   <li>{@code false} = 瞬时/未决，值得重试</li>
 * </ul>
 */
final class ReportRetryPolicy {

    private ReportRetryPolicy() {
    }

    /**
     * 该状态码是否代表"重试没意义"的终态。
     *
     * <p>4xx 一律终态：请求本身有问题（未实名/已注销/参数错误/签名过期），
     * 换个时间再发一样被拒。唯一例外是 408（超时）和 429（限流）——
     * 前者本身就是超时语义，后者通常带 {@code Retry-After}，都应退避后重试。
     *
     * <p>5xx 与 0（连接失败前的占位）都判为可重试。
     *
     * @param statusCode HTTP 状态码；0 表示尚未拿到响应（如连接被拒）
     * @return true = 不要重试
     */
    static boolean isTerminal(int statusCode) {
        if (statusCode == 0) {
            return false;
        }
        if (statusCode == 408 || statusCode == 429) {
            return false;   // 超时 / 限流：都是"再试一次可能成"
        }
        return statusCode >= 400 && statusCode < 500;
    }
}
