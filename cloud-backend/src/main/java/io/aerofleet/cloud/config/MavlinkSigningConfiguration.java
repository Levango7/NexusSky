package io.aerofleet.cloud.config;

import io.aerofleet.mavlink.security.MavlinkSignatureConfig;
import io.aerofleet.mavlink.security.MavlinkSignerFactory;
import io.aerofleet.mavlink.security.SigningKeyManager;
import io.aerofleet.mavlink.security.TimestampTracker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * MAVLink 链路签名组件装配。
 * <p>
 * <b>为什么需要这个类</b>：{@code UdpGateway} 把 {@link MavlinkSignerFactory}、
 * {@link SigningKeyManager}、{@link TimestampTracker}、{@link MavlinkSignatureConfig}
 * 全部声明为 {@code @Autowired(required = false)}，而 {@code CloudBackendApplication}
 * 是不带 {@code scanBasePackages} 的裸 {@code @SpringBootApplication}——只扫
 * {@code io.aerofleet.cloud}，签名类却都在 {@code io.aerofleet.mavlink.security} 包里，
 * mavlink-core 也没有自动配置文件。结果是这四个注入点<b>永远为 null</b>：
 * {@code UdpGateway.isSigningEnabled()} 恒 false，把 {@code mavlink.signing.enabled=true}
 * 配上也不会签名或验签，连 {@link MavlinkSignatureConfig#afterPropertiesSet()} 的启动校验
 * 都不会执行。此前 README 写的"签名代码已实现并接入 UdpGateway"只有无人机侧
 * （{@code VirtualDrone} 手工 new）成立，backend 半边是不实声明。
 * <p>
 * <b>出厂行为不变</b>：整个配置类带
 * {@code @ConditionalOnProperty(mavlink.signing.enabled=true)}。属性缺省或为 false 时
 * 这四个 bean 一概不创建，注入点仍是 null，与改动前逐字节一致；一旦显式打开，
 * "既没口令也没密钥库"会在启动阶段直接失败（fail-closed），而不是静默明文发送。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mavlink.signing", name = "enabled", havingValue = "true")
public class MavlinkSigningConfiguration {

    /**
     * 签名配置持有者。经 {@code @Bean} 注册后 Spring 仍会调用其
     * {@link MavlinkSignatureConfig#afterPropertiesSet()}，因此"开了签名却没给密钥"
     * 会在启动时报错而非运行时降级。
     */
    @Bean
    public MavlinkSignatureConfig mavlinkSignatureConfig() {
        return new MavlinkSignatureConfig();
    }

    /**
     * 签名器工厂：口令按 sysid 从 {@link SigningKeyManager} 取，单机全局口令与多机
     * 密钥库走同一条路径——因此只给 {@code key-store-path}（密钥库里含 defaultKey 或
     * per-sysid 条目）也能工作，不需要全局 {@code secret-key}。
     * <p>
     * "开了签名却一个密钥都没有"仍会在启动阶段失败，但由
     * {@link MavlinkSignatureConfig#afterPropertiesSet()} 统一裁决（secret-key 与
     * key-store-path 至少一个、且库文件必须存在），这里不再重复一套校验。
     */
    @Bean
    public MavlinkSignerFactory mavlinkSignerFactory(SigningKeyManager keyManager,
                                                     MavlinkSignatureConfig config) {
        return new MavlinkSignerFactory(keyManager, config.isRejectUnsigned());
    }

    /** 密钥管理器：给了 key-store-path 走多密钥库，否则用全局口令。 */
    @Bean
    public SigningKeyManager signingKeyManager(MavlinkSignatureConfig config) {
        if (config.isMultiMode()) {
            return new SigningKeyManager(Path.of(config.getKeyStorePath()), config.getSecretKey());
        }
        return new SigningKeyManager(config.getSecretKey());
    }

    /** 时间戳跟踪器（官方口径：严格递增 + 60 秒新流窗口，按 linkId/sysid/compid 分流）。 */
    @Bean
    public TimestampTracker mavlinkTimestampTracker() {
        return new TimestampTracker();
    }
}
