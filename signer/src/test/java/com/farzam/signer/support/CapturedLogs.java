package com.farzam.signer.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Everything written to any logger while a block of code runs.
 *
 * <p>Exists for one test — "no private key or master key appears in the logs" — and that test is
 * worth the class. Key hygiene is not a property of a function that can be asserted where it lives;
 * it is a property of every line of logging in the service, including the ones a future change adds.
 * A test that reads the output and looks for the secrets keeps holding after somebody adds a
 * diagnostic at three in the morning, which is exactly when it would be added.
 *
 * <p>The appender goes on the root logger at DEBUG, because the question is not whether the happy
 * path is quiet at INFO.
 *
 * <p>Stack traces are included in what is searched. An exception message is a log line: a provider
 * that put key material into one, or a {@code toString} on a configuration record that had not been
 * overridden, would reach a log file by that route and by no other.
 */
public final class CapturedLogs {

    private CapturedLogs() {}

    /**
     * Runs the work and returns what was logged.
     *
     * @param work what to do while listening
     * @return the captured output, whole and filterable
     */
    public static Captured whileRunning(Runnable work) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        try {
            work.run();
        } finally {
            root.setLevel(original);
            root.detachAppender(appender);
            appender.stop();
        }

        return new Captured(
                List.copyOf(appender.list)
                        .stream()
                        .map(event -> new Captured.Entry(event.getLoggerName(), render(event)))
                        .toList());
    }

    /**
     * What was logged, kept per event so that "who logged it" survives.
     *
     * <p>That distinction turns out to matter. Spring's {@code RestClient} logs entire request
     * bodies at DEBUG, so anything this service sends over HTTP is in the output whatever this
     * service's own logging does — which is fine while the only thing it sends is a signed
     * transaction that is about to be public on a chain, and would not be fine the day somebody has
     * it POST something else. Asserting separately on this application's own loggers is what keeps
     * that a deliberate state of affairs rather than an accident nobody re-checked.
     *
     * @param entries one per logging event, in order
     */
    public record Captured(List<Entry> entries) {

        /**
         * @param logger the logger's name
         * @param text the formatted message, plus any exception messages
         */
        public record Entry(String logger, String text) {}

        /**
         * @return everything, from every logger, including the frameworks'
         */
        public String everything() {
            return join(entries);
        }

        /**
         * @param loggerPrefix normally a package name
         * @return only what loggers under that name wrote
         */
        public String from(String loggerPrefix) {
            return join(entries.stream().filter(entry -> entry.logger().startsWith(loggerPrefix)).toList());
        }

        private static String join(List<Entry> lines) {
            return lines.stream().map(entry -> entry.logger() + " " + entry.text()).collect(Collectors.joining("\n"));
        }
    }

    private static String render(ILoggingEvent event) {
        StringBuilder rendered = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
            rendered.append('\n').append(thrown.getClassName()).append(": ").append(thrown.getMessage());
        }
        return rendered.toString();
    }
}
