package io.aerofleet.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiResponse} 的数据契约守卫。
 *
 * <p><b>为什么需要这组测试</b>：sdk-java 全模块只有 {@code DroneApiTest} 一个测试文件
 * （12 条），而它<b>只断言 method / path，从不读 {@code data}</b> —— stub 里精心准备的
 * {@code battery:85}、{@code lat:39.9} 这些载荷一次都没被读过。
 *
 * <p>实测（变异验证）证明这个缺口有多大：
 * <pre>
 *   把 @JsonProperty("data") 改名        -> 12 条仍全绿
 *   getDataAsList() 恒返回 null          -> 12 条仍全绿
 *   getDataAsMap()  恒返回 null          -> 12 条仍全绿
 *   getInt() 恒返回 0                    -> 12 条仍全绿
 * </pre>
 *
 * <p>也就是说：对外交付的 SDK，{@code data} 的 Jackson 映射、Map/List 类型判定、
 * 字段取值三条链路上<b>没有任何一处被钉住</b>。把 {@code data} 的 JSON 键名改错、
 * 或者 {@code getDataAsList()} 写坏，第三方集成方要等到运行时才发现。
 *
 * <p>本类把这条契约钉死，并刻意走 {@code Jackson} 真实反序列化
 * （而不是 {@code new ApiResponse(...)} 手工造对象）—— 否则测的只是 getter，
 * 而键名映射这条最容易出错的环节恰好被绕过去。
 */
@DisplayName("ApiResponse 数据契约（sdk-java 对外契约的唯一守卫）")
class ApiResponseContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("JSON 键名 data 能被正确映射（键名写错会让 data 永远为 null）")
    void jsonKeyDataIsMapped() throws Exception {
        ApiResponse r = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":{\"sysid\":1,\"battery\":85}}",
                ApiResponse.class);

        assertEquals("ok", r.getStatus());
        assertTrue(r.isOk());
        assertTrue(r.getDataAsMap() != null,
                "data 为对象时 getDataAsMap() 必须非 null —— 键名映射错了这里会挂");
        assertEquals(1, r.getInt("sysid"));
        assertEquals(85, r.getInt("battery"));
    }

    @Test
    @DisplayName("isOk 只认 ok（大小写不敏感），error/缺失均为 false")
    void isOkOnlyForOk() throws Exception {
        assertTrue(MAPPER.readValue("{\"status\":\"ok\"}", ApiResponse.class).isOk());
        assertTrue(MAPPER.readValue("{\"status\":\"OK\"}", ApiResponse.class).isOk());
        assertFalse(MAPPER.readValue("{\"status\":\"error\"}", ApiResponse.class).isOk());
        assertFalse(MAPPER.readValue("{\"data\":{}}", ApiResponse.class).isOk(),
                "status 缺失时不得判为成功");
        assertFalse(MAPPER.readValue("{\"status\":\"okay\"}", ApiResponse.class).isOk(),
                "前缀相同不等于 ok");
    }

    @Test
    @DisplayName("Map / List 类型判定互斥：data 是对象时 getDataAsList() 必须为 null")
    void mapAndListAreMutuallyExclusive() throws Exception {
        ApiResponse mapResp = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":{\"sysid\":1}}", ApiResponse.class);
        assertTrue(mapResp.getDataAsMap() != null);
        assertNull(mapResp.getDataAsList(), "对象不是列表");

        ApiResponse listResp = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":[{\"sysid\":1},{\"sysid\":2}]}",
                ApiResponse.class);
        assertTrue(listResp.getDataAsList() != null);
        assertNull(listResp.getDataAsMap(), "列表不是 Map");
        assertEquals(2, listResp.getDataAsList().size());
    }

    @Test
    @DisplayName("data 是 List 时，元素类型判定跟随真实反序列化结果")
    void listElementsAreMaps() throws Exception {
        ApiResponse r = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":[{\"sysid\":1},{\"sysid\":2}]}",
                ApiResponse.class);
        List<Object> raw = r.getDataAsList();
        assertEquals(2, raw.size());
        for (Object o : raw) {
            assertTrue(o instanceof Map, "列表元素应为 Map，实际是 " + o.getClass());
        }
    }

    @Test
    @DisplayName("get 族：不存在 / 非 Map 情形一律返回 null，不抛")
    void getFamilyReturnsNullOnMiss() throws Exception {
        ApiResponse r = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":{\"a\":\"x\",\"b\":42}}", ApiResponse.class);

        assertEquals("x", r.get("a"));
        assertEquals("42", r.getString("b"));
        assertNull(r.get("missing"));
        assertNull(r.getString("missing"));

        // data 是列表时 get 也应返回 null（而不是抛 ClassCastException）
        ApiResponse listResp = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":[1,2]}", ApiResponse.class);
        assertNull(listResp.get("a"), "data 非 Map 时 get() 应返回 null");
        assertNull(listResp.getString("a"));
    }

    @Test
    @DisplayName("getInt 的两条抛点：字段缺失 / 类型不匹配")
    void getIntThrowsOnMissAndTypeMismatch() throws Exception {
        ApiResponse r = MAPPER.readValue(
                "{\"status\":\"ok\",\"data\":{\"n\":42,\"s\":\"hello\",\"d\":3.9}}",
                ApiResponse.class);

        assertEquals(42, r.getInt("n"));

        assertThrows(SdkException.class, () -> r.getInt("missing"),
                "字段缺失应抛");
        assertThrows(SdkException.class, () -> r.getInt("s"),
                "非数字字段应抛");

        // 陷阱：Jackson 把小数读成 Double，Double 是 Number，intValue() 会静默截断
        assertEquals(3, r.getInt("d"),
                "Double 是 Number，intValue() 截断 —— 这是一条已存在的语义，"
                        + "本测试把它钉住以免被无声改动");
    }

    @Test
    @DisplayName("构造器直调也成立（不只 Jackson 路径）")
    void constructorPathAlsoHolds() {
        ApiResponse r = new ApiResponse("ok", Map.of("k", 1));
        assertTrue(r.isOk());
        assertEquals(1, r.getInt("k"));
        assertEquals("1", r.getString("k"));
    }
}