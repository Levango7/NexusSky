package io.aerofleet.cloud.surveillance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 测试用日志旁听器：把指定 logger 的输出接到内存列表，供断言"这条日志必须有/必须没有"。
 * <p>
 * 为什么不猜日志文本：warn/info 是运维可观测契约的一部分（例如目录解析跳过条目
 * 必须 warn、InfoSummary 计数必须正确），mock 掉 logger 就等于丢了这部分行为。
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

    /** 旁听过程中收到过的、级别 ≥ level 的日志行。 */
    List<String> linesAtLeast(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }
}
