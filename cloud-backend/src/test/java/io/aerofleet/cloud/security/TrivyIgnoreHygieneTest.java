package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `.trivyignore.yaml` 的卫生门禁：忽略清单是**安保资产**，不允许退化成静默白名单。
 *
 * <p>每条必须同时满足四点，缺一即红：
 * <ol>
 *   <li>{@code paths} 非空——否则条目对**全仓所有文件**生效，一条 CVE 的局部评估会变成全局放行；</li>
 *   <li>{@code expired_at} 存在且可解析——到期后 Trivy（v0.70.0 {@code pkg/result/ignore.go}
 *       的 {@code Prune}）会剔除条目、发现自动回来判红；没有到期日 = 永久放行；</li>
 *   <li>{@code statement} 足够长——必须写出可达性证据与撤销条件，而不是一句"已知漏洞"；</li>
 *   <li>{@code id} 非空——否则压的是什么都没说清。</li>
 * </ol>
 *
 * <p>字段名与过期语义都以**固定版本 v0.70.0 的源码**为准（本仓跑的是
 * trivy-action@v0.36.0 内置的 trivy v0.70.0），不照抄网页说明。
 */
@DisplayName(".trivyignore.yaml 卫生门禁")
class TrivyIgnoreHygieneTest {

    /** 从模块目录向上找仓库根（surefire 的 user.dir 是 cloud-backend/）。 */
    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isRegularFile(p.resolve(".trivyignore.yaml"))
                    || Files.isRegularFile(p.resolve(".git"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    @Test
    @DisplayName("每条忽略都必须限定路径、带到期时间、写出证据")
    void everyIgnoreEntryIsScopedBoundedAndJustified() throws Exception {
        Path root = repoRoot();
        assertNotNull(root, "找不到仓库根（应从 cloud-backend/ 向上找到 .git）");
        Path ignoreFile = root.resolve(".trivyignore.yaml");
        assertTrue(Files.isRegularFile(ignoreFile), "忽略清单不存在：" + ignoreFile);

        Map<String, Object> config;
        try (InputStream in = Files.newInputStream(ignoreFile)) {
            config = new Yaml().load(in);
        }
        assertNotNull(config, "忽略清单解析为空");
        assertTrue(config.containsKey("vulnerabilities"),
                "只允许在 vulnerabilities 段登记（误配到 secrets/licenses 段会改变语义）");

        List<Map<String, Object>> entries = (List<Map<String, Object>>) config.get("vulnerabilities");
        assertNotNull(entries, "vulnerabilities 段格式不对");

        for (Map<String, Object> entry : entries) {
            String id = String.valueOf(entry.get("id"));
            assertTrue(id.startsWith("CVE-") || id.startsWith("GHSA-"),
                    "条目 id 非法/缺失：" + entry);

            Object paths = entry.get("paths");
            assertTrue(paths instanceof List<?> list && !list.isEmpty(),
                    id + "：缺 paths —— 不限定路径的忽略会对全仓所有文件生效");

            Object expiredAt = entry.get("expired_at");
            assertNotNull(expiredAt, id + "：缺 expired_at —— 无到期日等于永久放行");
            String raw = String.valueOf(expiredAt);
            // snakeyaml 可能把它解析成 Date/Instant；两种形态都要能落到一个真实时间点
            if (expiredAt instanceof java.util.Date d) {
                assertTrue(d.getTime() > 0, id + "：expired_at 解析为无效时间");
            } else {
                OffsetDateTime.parse(raw.replace("Z", "+00:00").replace(" ", "T"),
                        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }

            String statement = String.valueOf(entry.getOrDefault("statement", ""));
            assertTrue(statement.length() >= 100,
                    id + "：statement 太短（" + statement.length() + " 字符）——"
                            + "必须写可达性证据与撤销条件，否则这条忽略没有可复核的依据");
        }
    }
}
