package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 钉测：truth HTTP 的监听地址可配置（容器部署跨容器访问 truth 的前提）。
 *
 * 此前 `TargetStateServer.start()` 硬编码 `new InetSocketAddress("127.0.0.1", port)`——
 * 即使 drone-sim 开 `--http-port`，truth server 也只绑容器内回环，后端在另一容器/Pod
 * 无法访问（非 host 部署 vision truth 断链的根因）。
 *
 * 默认仍 127.0.0.1（本地开发安全）；新增可配置 bindIp（容器部署设 0.0.0.0）。
 * `configurableToAnyLocal` 在修复前**编译失败**（无带 bindIp 的构造函数）——先红后绿。
 */
class TargetStateServerBindTest {

    /** 反射读 TargetStateServer 私有 server 字段的实际监听地址。 */
    private static InetSocketAddress addressOf(TargetStateServer s) throws Exception {
        Field f = TargetStateServer.class.getDeclaredField("server");
        f.setAccessible(true);
        return (InetSocketAddress) ((com.sun.net.httpserver.HttpServer) f.get(s)).getAddress();
    }

    private static void stopQuietly(TargetStateServer s) throws Exception {
        Field f = TargetStateServer.class.getDeclaredField("server");
        f.setAccessible(true);
        ((com.sun.net.httpserver.HttpServer) f.get(s)).stop(0);
    }

    @Test
    void defaultsToLoopback() throws Exception {
        // 旧 54 全参数构造（bindIp 默认 127.0.0.1）：保持现状，本地开发安全
        TargetStateServer s = new TargetStateServer(null, 0,
                ArrayList::new, () -> 0.0, seq -> null);
        s.start();
        assertEquals("127.0.0.1", addressOf(s).getAddress().getHostAddress());
        stopQuietly(s);
    }

    @Test
    void configurableToAnyLocal() throws Exception {
        // 新增带 bindIp 的构造：容器部署设 0.0.0.0，后端在另一容器/Pod 可访问 truth。
        // 修复前此构造不存在（编译失败）——先红后绿。
        TargetStateServer s = new TargetStateServer(null, 0,
                ArrayList::new, () -> 0.0, seq -> null, "0.0.0.0");
        s.start();
        assertTrue(addressOf(s).getAddress().isAnyLocalAddress(),
                "bindIp=0.0.0.0 应绑通配地址（0.0.0.0 或 ::，跨容器可访问）");
        stopQuietly(s);
    }
}
