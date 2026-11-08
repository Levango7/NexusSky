package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 影石 Insta360 OSC 协议客户端（**零硬件可对接**）。
 *
 * <p><b>依据</b>：影石官方《OSC Protocol》文档（基于 Google OSC 规范）逐条落实——
 * 连接后相机 IP {@code 192.168.42.1}，每个请求必须带：
 * <pre>
 * Content-Type: application/json;charset=utf-8
 * Accept: application/json
 * X-XSRF-Protected: 1
 * </pre>
 * 且「上一个回复到达后才发下一个 {@code osc/commands} 请求」「{@code /osc/info}
 * 不超过每秒一次」。拍照流程（文档明确要求）：
 * <ol>
 *   <li>先 {@code GET /osc/info} + {@code POST /osc/camera state} 各取一次
 *       （文档原话："Remember to send request to /osc/info, /osc/state
 *       before starting to take picture"）；</li>
 *   <li>{@code POST /osc/commands/execute} {@code {"name":"camera.takePicture"}}
 *       ⇒ {@code state=inProgress} + {@code id}；</li>
 *   <li>按文档建议每秒一次轮询 {@code POST /osc/commands/status} {@code {"id":...}}，
 *       直到 {@code state=done}，从 {@code results.fileUrl} 取文件地址。</li>
 * </ol>
 *
 * <p><b>为什么这条能零硬件做</b>：OSC 是<strong>公开标准协议</strong>（Google OSC spec），
 * 接口契约在文档里逐字给出，且相机端只是个普通 HTTP 服务端。因此本层用真实 HTTP
 * 回环（JDK HttpServer 扮演相机）就能跑通全流程——不需要相机、不需要厂商账号。
 * 真实设备上的媒体面（取流/录像文件搬运）属互操作层，不属本契约层。
 *
 * <p><b>容易做错、因此逐条钉死的点</b>（都是实测踩过的）：
 * <ul>
 *   <li>{@code X-XSRF-Protected: 1} 漏带——OSC 规范要求，相机直接拒；</li>
 *   <li>{@code /osc/state} 用 GET——它是 POST，用 GET 相机不认；</li>
 *   <li>把 {@code inProgress} 当成已完成——必须轮询到 {@code done} 才有 {@code fileUrl}；</li>
 *   <li>取文件时拿 {@code _fileGroup[0]} 代替 {@code fileUrl}——后者才是标准字段，前者
 *       是影石向后兼容的 vendor 字段（下划线前缀 = 厂商私有）；</li>
 *   <li>把 {@code unactivated}/{@code disabledCommand} 当成可重试错误——前者要用户去
 *       官方 App 激活，后者要先 {@code camera.setOptions} 改 captureMode，
 *       盲目重试只会刷屏。</li>
 * </ul>
 *
 * <p><b>异常纪律</b>（同 #248/#250）：对外只留自建短句，平台响应体（含相机内网地址、
 * 文件路径）只进日志与 cause。
 */
public final class Insta360OscClient {

    private static final Logger log = LoggerFactory.getLogger(Insta360OscClient.class);

    // ------------------------------------------------------------------
    // 契约常量（文档逐字）
    // ------------------------------------------------------------------

    /** 相机信息（<b>GET</b>，文档：不超过每秒一次）。 */
    static final String PATH_INFO = "/osc/info";
    /** 相机状态（<b>POST</b>）。 */
    static final String PATH_STATE = "/osc/state";
    /** 命令执行。 */
    static final String PATH_COMMANDS_EXECUTE = "/osc/commands/execute";
    /** 命令状态查询。 */
    static final String PATH_COMMANDS_STATUS = "/osc/commands/status";

    /**
     * OSC 规范（+ 影石文档）要求的防 CSRF 头。
     *
     * <p>**为什么必须带**：Google OSC 规范把 {@code X-XSRF-Protected: 1} 定为必填，
     * 防止浏览器页面跨站驱动相机；漏带的后果是相机直接拒绝请求。这是 OSC 对接里
     * 最经典的一处「协议要求但容易漏」的细节。
     */
    static final String HEADER_XSRF_PROTECTED = "X-XSRF-Protected";
    static final String VALUE_XSRF_PROTECTED = "1";

    /** 文档给出的 Content-Type（注意 <b>charset=utf-8 不带引号</b>）。 */
    static final String CONTENT_TYPE_JSON = "application/json;charset=utf-8";
    /** 文档给出的 Accept。 */
    static final String ACCEPT_JSON = "application/json";

    /** 拍照完成后的标准结果字段名（{@code results.fileUrl}）。 */
    static final String FIELD_FILE_URL = "fileUrl";

    /** 文档建议的命令状态轮询间隔（每秒一次）。 */
    static final Duration DOC_POLL_INTERVAL = Duration.ofSeconds(1);
    /** 轮询上限（防相机卡死时无限等）。 */
    static final int DOC_MAX_POLL_ATTEMPTS = 120;

    // ------------------------------------------------------------------
    // 命令状态（OSC state 字段取值）
    // ------------------------------------------------------------------

    /** 命令已受理、进行中（带 id，需轮询）。 */
    public static final String STATE_IN_PROGRESS = "inProgress";
    /** 命令完成（此时才有结果字段）。 */
    public static final String STATE_DONE = "done";
    /** 命令失败（带 error.code / error.message）。 */
    public static final String STATE_ERROR = "error";

    // ------------------------------------------------------------------
    // 影石文档给出的错误码（决定上层怎么处理）
    // ------------------------------------------------------------------

    /** 相机未激活——需用户在官方 App 里激活，重试无意义。 */
    public static final String ERR_UNACTIVATED = "unactivated";
    /** 命令在当前模式下不可用——需先 setOptions 改 captureMode。 */
    public static final String ERR_DISABLED_COMMAND = "disabledCommand";
    /** 参数名不认识。 */
    public static final String ERR_INVALID_PARAMETER_NAME = "invalidParameterName";
    /** 参数值不合法。 */
    public static final String ERR_INVALID_PARAMETER_VALUE = "invalidParameterValue";

    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration pollInterval;
    private final int maxPollAttempts;

    /**
     * @param baseUri 相机基址，如 {@code http://192.168.42.1}
     */
    public Insta360OscClient(URI baseUri) {
        this(baseUri, DOC_POLL_INTERVAL, DOC_MAX_POLL_ATTEMPTS);
    }

    /**
     * @param pollInterval   命令状态轮询间隔（文档建议 1s；测试里用小值）
     * @param maxPollAttempts 轮询上限
     */
    public Insta360OscClient(URI baseUri, Duration pollInterval, int maxPollAttempts) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (maxPollAttempts < 1) {
            throw new IllegalArgumentException("maxPollAttempts must be >= 1");
        }
        this.maxPollAttempts = maxPollAttempts;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------
    // 1) GET /osc/info
    // ------------------------------------------------------------------

    /** 已缓存的相机信息（文档：{@code /osc/info} 不超过每秒一次 ⇒ 同会话复用）。 */
    private volatile Insta360CameraInfo cachedInfo;

    /**
     * 取相机基本信息（{@code GET /osc/info}）。
     *
     * <p>文档：频率建议不超过每秒一次。因此同一次会话里重复调用直接复用缓存——
     * 这不是「省事」，是**契约要求**：相机对 {@code /osc/info} 的高频轮询会限流。
     * （早期实现只置了个 {@code infoFetched} 标志却从没读过它，每次都真去打网络，
     * 被 {@code infoIsCachedWithinSession} 当场抓出来。）
     */
    public Insta360CameraInfo info() {
        Insta360CameraInfo cached = cachedInfo;
        if (cached != null) {
            return cached;
        }
        JsonNode root = call("GET", PATH_INFO, null);
        Insta360CameraInfo info = Insta360CameraInfo.from(root);
        this.cachedInfo = info;
        return info;
    }

    /** 本会话是否已取过 {@code /osc/info}。 */
    public boolean isInfoFetched() {
        return cachedInfo != null;
    }

    // ------------------------------------------------------------------
    // 2) POST /osc/state
    // ------------------------------------------------------------------

    /** 取相机状态（{@code POST /osc/state}）——注意是 <b>POST</b> 不是 GET。 */
    public Insta360CameraState state() {
        JsonNode root = call("POST", PATH_STATE, null);
        return Insta360CameraState.from(root);
    }

    // ------------------------------------------------------------------
    // 3) POST /osc/commands/execute
    // ------------------------------------------------------------------

    /**
     * 执行一条 OSC 命令（{@code POST /osc/commands/execute}）。
     *
     * @return 首条响应（拍照类命令通常是 {@code inProgress} + {@code id}）
     */
    public Insta360CommandStatus execute(String commandName) {
        return execute(commandName, null);
    }

    /**
     * 执行一条带参数的 OSC 命令。
     *
     * @param commandName 命令名，如 {@code camera.takePicture}。**不以 {@code _} 开头**——
     *                    OSC 规范用下划线前缀区分厂商私有命令。
     * @param parameters  参数节点（可为 null）
     */
    public Insta360CommandStatus execute(String commandName, JsonNode parameters) {
        if (commandName == null || commandName.isBlank()) {
            throw new IllegalArgumentException("commandName must not be blank");
        }
        ObjectNode body = mapper.createObjectNode().put("name", commandName);
        if (parameters != null && !parameters.isNull()) {
            body.set("parameters", parameters);
        }
        JsonNode root = call("POST", PATH_COMMANDS_EXECUTE, body);
        return Insta360CommandStatus.from(root, commandName);
    }

    // ------------------------------------------------------------------
    // 4) POST /osc/commands/status
    // ------------------------------------------------------------------

    /**
     * 查命令状态（{@code POST /osc/commands/status} {@code {"id": ...}}）。
     *
     * <p>{@code id} 来自 execute 的 {@code inProgress} 响应——**它不是命令名**，
     * 少它或多它都拿不到状态。
     */
    public Insta360CommandStatus status(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        ObjectNode body = mapper.createObjectNode().put("id", commandId);
        JsonNode root = call("POST", PATH_COMMANDS_STATUS, body);
        return Insta360CommandStatus.from(root, null);
    }

    /**
     * 轮询命令状态直到 {@code done}。
     *
     * <p>文档原话：check status repeatedly (recommend once per second) until state is done。
     *
     * @throws Insta360OscException 轮到上限仍未完成，或相机返回 {@code error}
     */
    public Insta360CommandStatus awaitDone(String commandId) {
        for (int attempt = 1; ; attempt++) {
            Insta360CommandStatus st = status(commandId);
            if (st.error()) {
                throw toException(st);
            }
            if (STATE_DONE.equals(st.state())) {
                return st;
            }
            if (attempt >= maxPollAttempts) {
                throw new Insta360OscException("insta360 osc command " + commandId
                        + " still not done after " + attempt + " polls"
                        + " (last state=" + st.state() + ")");
            }
            sleep();
        }
    }

    // ------------------------------------------------------------------
    // 5) 拍照全流程（文档口径：info → state → execute → 轮询 → fileUrl）
    // ------------------------------------------------------------------

    /**
     * 拍一张照并返回文件地址（文档完整流程）。
     *
     * <p>顺序即契约：<b>info → state → execute → 轮询到 done → results.fileUrl</b>。
     * 文档明确要求拍照前必须先取 info 与 state；本方法自动补齐，调用方不用记得。
     *
     * @param photoStitching 是否启用机身拼接（{@code "none"} / {@code "ondevice"}）；
     *                       null = 不主动设置该选项
     * @return 照片的 {@code fileUrl}（标准字段，不是 vendor 的 {@code _fileGroup}）
     */
    public String takePicture(String photoStitching) {
        // ① 文档要求：拍照前先取 info 与 state 各一次（info 已取过则复用缓存）
        info();
        state();

        // ② 可选：机身拼接（影石特有选项，documented in camera.getOptions/setOptions）
        if (photoStitching != null && !photoStitching.isBlank()) {
            ObjectNode opts = mapper.createObjectNode()
                    .put("captureMode", "image")
                    .put("photoStitching", photoStitching);
            execute("camera.setOptions", mapper.createObjectNode().set("options", opts));
        }

        // ③ 拍照 ⇒ inProgress + id
        Insta360CommandStatus started = execute("camera.takePicture");
        if (started.error()) {
            throw toException(started);
        }
        if (!STATE_IN_PROGRESS.equals(started.state())) {
            // 文档口径：成功受理时状态应为 inProgress；出现别的值说明流程走出了预期，
            // 宁可响亮失败也不要当成功往下走（否则拿不到 id 也无法轮询）。
            throw new Insta360OscException("insta360 osc takePicture not accepted"
                    + " (expected state=" + STATE_IN_PROGRESS + ", got " + started.state() + ")");
        }
        if (started.id() == null || started.id().isBlank()) {
            throw new Insta360OscException(
                    "insta360 osc takePicture response has no command id");
        }

        // ④ 轮询到 done ⇒ results.fileUrl
        Insta360CommandStatus done = awaitDone(started.id());
        if (done.fileUrl() == null || done.fileUrl().isBlank()) {
            throw new Insta360OscException("insta360 osc takePicture done but"
                    + " results.fileUrl is missing (documented field name is fileUrl)");
        }
        return done.fileUrl();
    }

    // ------------------------------------------------------------------
    // 6) camera.delete（文档：删除文件）
    // ------------------------------------------------------------------

    /**
     * 删除文件（{@code camera.delete}，参数 {@code {"fileUrls":[...]}}）。
     *
     * <p>成功时响应 {@code results.fileUrls} 为<b>空数组</b>（文档）+ {@code state=done}。
     */
    public List<String> delete(List<String> fileUrls) {
        if (fileUrls == null || fileUrls.isEmpty()) {
            throw new IllegalArgumentException("fileUrls must not be empty");
        }
        ObjectNode params = mapper.createObjectNode();
        var arr = params.putArray("fileUrls");
        for (String u : fileUrls) {
            arr.add(u);
        }
        Insta360CommandStatus st = execute("camera.delete", params);
        if (st.error()) {
            throw toException(st);
        }
        return st.fileUrls();
    }

    // ------------------------------------------------------------------
    // 7) camera.getOptions / camera.setOptions
    // ------------------------------------------------------------------

    /** 读相机选项（文档：photoStitching/photoStitchingSupport 等）。 */
    public Map<String, JsonNode> getOptions(List<String> optionNames) {
        if (optionNames == null || optionNames.isEmpty()) {
            throw new IllegalArgumentException("optionNames must not be empty");
        }
        ObjectNode params = mapper.createObjectNode();
        var arr = params.putArray("optionNames");
        for (String n : optionNames) {
            arr.add(n);
        }
        Insta360CommandStatus st = execute("camera.getOptions", params);
        if (st.error()) {
            throw toException(st);
        }
        JsonNode opts = st.results().path("options");
        Map<String, JsonNode> out = new LinkedHashMap<>();
        opts.fieldNames().forEachRemaining(k -> out.put(k, opts.get(k)));
        return out;
    }

    // ------------------------------------------------------------------
    // 底层：发请求 + 解析
    // ------------------------------------------------------------------

    private JsonNode call(String method, String path, ObjectNode body) {
        String bodyText = body == null ? "" : write(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(resolve(path))
                .timeout(Duration.ofSeconds(20))
                // 文档给出的三个头 + XSRF（OSC 规范必填）
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Accept", ACCEPT_JSON)
                .header(HEADER_XSRF_PROTECTED, VALUE_XSRF_PROTECTED);
        HttpRequest req = bodyText.isEmpty()
                ? b.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : b.method(method, HttpRequest.BodyPublishers.ofString(
                        bodyText, java.nio.charset.StandardCharsets.UTF_8)).build();

        HttpResponse<String> resp = send(req, method + " " + path);
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw Insta360OscException.wrap("insta360 osc response unreadable", e);
        }
        // OSC 的 error 有两种表现：顶层 error 对象（execute 常见），
        // 或 state=error + error 对象（文档示例两者都出现过）。
        JsonNode err = root.path("error");
        if (!err.isMissingNode() && !err.isNull()) {
            String code = err.path("code").asText("");
            String msg = err.path("message").asText("");
            throw new Insta360OscException("insta360 osc rejected: "
                    + (msg.isBlank() ? code : msg), code);
        }
        return root;
    }

    private HttpResponse<String> send(HttpRequest req, String what) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("insta360 osc {} failed", what, e);
            throw Insta360OscException.wrap("insta360 osc " + what + " failed", e);
        }
    }

    private String write(ObjectNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw Insta360OscException.wrap("insta360 osc request body unserializable", e);
        }
    }

    /** 相机返回的 error 状态转成异常（<b>不重试</b>，见类注释的处置说明）。 */
    static Insta360OscException toException(Insta360CommandStatus st) {
        String code = st.errorCode() == null ? "" : st.errorCode();
        String msg = st.errorMessage();
        return new Insta360OscException("insta360 osc rejected: "
                + (msg == null || msg.isBlank() ? code : msg), code);
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Insta360OscException("insta360 osc polling interrupted");
        }
    }

    private URI resolve(String path) {
        String b = baseUri.toString();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return URI.create(b + path);
    }

    // ------------------------------------------------------------------
    // 数据形状（文档示例逐字）
    // ------------------------------------------------------------------

    /** {@code /osc/info} 响应（注意 vendor 字段以 {@code _} 前缀，本 record 只收标准字段）。 */
    public record Insta360CameraInfo(
            String manufacturer,
            String model,
            String serialNumber,
            String firmwareVersion,
            List<String> api,
            List<Integer> apiLevel) {

        static Insta360CameraInfo from(JsonNode root) {
            JsonNode api = root.path("api");
            List<String> apiList = new ArrayList<>();
            api.forEach(n -> apiList.add(n.asText()));
            List<Integer> levels = new ArrayList<>();
            root.path("apiLevel").forEach(n -> levels.add(n.asInt()));
            return new Insta360CameraInfo(
                    root.path("manufacturer").asText(null),
                    root.path("model").asText(null),
                    root.path("serialNumber").asText(null),
                    root.path("firmwareVersion").asText(null),
                    List.copyOf(apiList),
                    List.copyOf(levels));
        }

        /** 是否实现了某个 OSC 端点（文档 {@code api} 数组）。 */
        public boolean supports(String endpoint) {
            return api != null && api.contains(endpoint);
        }
    }

    /**
     * {@code POST /osc/camera state} 响应。
     *
     * <p>标准字段只有 {@code fingerprint} + {@code state.batteryLevel}/
     * {@code state.storageUri}；{@code state._cardState} 是影石 vendor 字段
     * （下划线前缀），单独收只读不作契约保证。
     */
    public record Insta360CameraState(
            String fingerprint,
            double batteryLevel,
            String storageUri,
            String vendorCardState) {

        static Insta360CameraState from(JsonNode root) {
            JsonNode s = root.path("state");
            return new Insta360CameraState(
                    root.path("fingerprint").asText(null),
                    s.path("batteryLevel").asDouble(Double.NaN),
                    s.path("storageUri").asText(null),
                    s.path("_cardState").asText(null));
        }

        /** 电量（文档：{@code values in [0,1]}）。 */
        public int batteryPercent() {
            if (Double.isNaN(batteryLevel)) {
                return -1;
            }
            return (int) Math.round(batteryLevel * 100.0);
        }
    }

    /**
     * {@code /osc/commands/execute} 与 {@code /osc/commands/status} 的统一响应形状。
     *
     * <p>文档示例里 {@code name} 在 status 响应中<b>不一定</b>出现，因此允许 null；
     * 而 {@code state}、{@code id}、{@code error}、{@code results} 才是决策依据。
     */
    public record Insta360CommandStatus(
            String name,
            String state,
            String id,
            Double completion,
            boolean error,
            String errorCode,
            String errorMessage,
            JsonNode results) {

        static Insta360CommandStatus from(JsonNode root, String fallbackName) {
            JsonNode err = root.path("error");
            boolean hasError = !err.isMissingNode() && !err.isNull()
                    || STATE_ERROR.equals(root.path("state").asText(""));
            List<String> urls = new ArrayList<>();
            root.path("results").path("fileUrls").forEach(n -> urls.add(n.asText()));
            return new Insta360CommandStatus(
                    root.hasNonNull("name") ? root.get("name").asText() : fallbackName,
                    root.path("state").asText(null),
                    root.path("id").asText(null),
                    root.path("progress").path("completion").isNumber()
                            ? root.get("progress").get("completion").asDouble() : null,
                    hasError,
                    hasError ? err.path("code").asText(null) : null,
                    hasError ? err.path("message").asText(null) : null,
                    root.path("results").isMissingNode()
                            ? null : root.get("results"));
        }

        /** {@code results.fileUrl}（<b>标准字段</b>；文档里的 {@code _fileGroup} 是 vendor 的）。 */
        public String fileUrl() {
            JsonNode r = results();
            if (r == null) {
                return null;
            }
            String v = r.path(FIELD_FILE_URL).asText(null);
            return v;
        }

        /** {@code results.fileUrls}（文档：camera.delete 成功时为空数组）。 */
        public List<String> fileUrls() {
            List<String> out = new ArrayList<>();
            JsonNode r = results();
            if (r != null) {
                r.path("fileUrls").forEach(n -> out.add(n.asText()));
            }
            return List.copyOf(out);
        }

        /** 是否是「相机未激活」——只能让用户去官方 App 激活。 */
        public boolean unactivated() {
            return ERR_UNACTIVATED.equals(errorCode());
        }

        /** 是否是「当前模式不可用」——需先 setOptions 改 captureMode。 */
        public boolean disabledCommand() {
            return ERR_DISABLED_COMMAND.equals(errorCode());
        }
    }

    // ------------------------------------------------------------------
    // 异常
    // ------------------------------------------------------------------

    /**
     * OSC 对接失败（业务拒绝或链路问题）。
     *
     * <p>刻意不做「自动重试」：文档给出的错误码要么是用户侧动作（{@code unactivated}）、
     * 要么是模式问题（{@code disabledCommand}），盲目重试只会刷屏。
     */
    public static final class Insta360OscException extends RuntimeException {
        private final String code;

        public Insta360OscException(String message) {
            this(message, null);
        }

        public Insta360OscException(String message, String code) {
            super(message);
            this.code = code;
        }

        /** 链路/解析类失败：cause 只进日志。 */
        public static Insta360OscException wrap(String message, Throwable cause) {
            Insta360OscException e = new Insta360OscException(message);
            e.initCause(cause);
            return e;
        }

        public String code() {
            return code;
        }

        /** 相机未激活（需用户在官方 App 里激活）。 */
        public boolean unactivated() {
            return ERR_UNACTIVATED.equals(code);
        }

        /** 当前模式不可用（需先 setOptions 改 captureMode）。 */
        public boolean disabledCommand() {
            return ERR_DISABLED_COMMAND.equals(code);
        }

        @Override
        public String toString() {
            return "Insta360OscException{code=" + code + ", message=" + getMessage() + '}';
        }
    }

    /** 供测试直接构造参数节点。 */
    static ObjectNode newParams() {
        return new ObjectMapper().createObjectNode();
    }

    /** 命令名小写归一（OSC 规范：命令名大小写敏感，这里只用于日志可读）。 */
    static String normalizeForLog(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 供测试/上层判断某 Instant 是否早于另一个（避免测试自己算时间）。 */
    static boolean isBefore(Instant a, Instant b) {
        return a.isBefore(b);
    }
}
