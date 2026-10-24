package io.aerofleet.cloud.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 测试用日志旁听器（security 包自包含副本）。
 * <p>
 * 本仓门禁测试直接实例化被测类、不起 Spring 上下文，审计归属这类「只落在日志行」的
 * 行为只能靠旁听 logger 断言。surveillance 包有一份同款工具，但那是另一个 PR 的
 * 新增文件——为让本 PR 相对 master 自包含（不产生跨 PR 编译依赖），这里放一份
 * 精简副本；两个包的旁听互不影响（logger 是按类隔离的）。
 * <p>
 * 用 try-with-resources 保证用完摘除，不污染同线程里跑的后续测试。
 */
final class LogWiretap implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level originalLevel;

    private LogWiretap(Logger logger) {
        this.logger = logger;
        this.originalLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    /** 旁听某个类的 logger。 */
    static LogWiretap on(Class<?> loggingClass) {
        return new LogWiretap((Logger) LoggerFactory.getLogger(loggingClass));
    }

    /** 旁听过程中收到过的全部日志行（格式化后的消息文本）。 */
    List<String> lines() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }
}
