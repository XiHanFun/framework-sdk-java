package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * 插件写给宿主的日志（{@code invocation.md} §6）：stderr 上一行一个 JSON 对象，{@code level} 是
 * {@code trace} / {@code debug} / {@code information} / {@code warning} / {@code error} / {@code critical}，{@code message} 是文案，
 * 其余成员是作用域属性；宿主逐行转为自己的日志，来源 {@code plugins.<清单 ID>}。
 *
 * <p>线程安全：一行在锁里整行写出，并发的日志不会交错。
 */
public final class PluginLogger {
    /** 写日志行；不做任何转义之外的格式化。 */
    private static final JsonFactory JSON = new JsonFactory();

    /** 日志级别：协议里的级别名，按严重程度升序。 */
    public enum Level {
        /** 最细的诊断。 */
        TRACE("trace"),
        /** 调试。 */
        DEBUG("debug"),
        /** 一般信息。 */
        INFORMATION("information"),
        /** 告警。 */
        WARNING("warning"),
        /** 错误。 */
        ERROR("error"),
        /** 致命错误。 */
        CRITICAL("critical");

        /** 协议里的级别名。 */
        private final String wireName;

        Level(String wireName) {
            this.wireName = wireName;
        }

        /**
         * 返回协议里的级别名。
         *
         * @return 级别名，如 {@code information}
         */
        public String wireName() {
            return wireName;
        }
    }

    private final Sink sink;
    private final Level minimum;

    /** 每一行都带的作用域属性，按加入顺序。 */
    private final Map<String, Object> scope;

    private PluginLogger(Sink sink, Level minimum, Map<String, Object> scope) {
        this.sink = sink;
        this.minimum = minimum;
        this.scope = scope;
    }

    /**
     * 创建写到进程 stderr 的日志，按 UTF-8 写出，最低级别 {@link Level#INFORMATION}。
     *
     * @return 日志
     */
    public static PluginLogger standardError() {
        return of(new FileOutputStream(FileDescriptor.err), Level.INFORMATION);
    }

    /**
     * 创建写到给定输出的日志。
     *
     * @param target 输出；每行写完即刷新
     * @param minimum 最低级别，低于它的日志丢弃
     * @return 日志
     */
    public static PluginLogger of(OutputStream target, Level minimum) {
        return new PluginLogger(new Sink(Objects.requireNonNull(target, "target")), Objects.requireNonNull(minimum, "minimum"), Map.of());
    }

    /**
     * 派生一个每行都多带一个属性的日志，与本日志写到同一个输出。
     *
     * @param name 属性名
     * @param value 属性值；字符串、数字与布尔原样写出，其他值写成 {@code toString()}
     * @return 新的日志
     */
    public PluginLogger with(String name, Object value) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(scope);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return new PluginLogger(sink, minimum, copy);
    }

    /**
     * 判断某个级别是否会写出。
     *
     * @param level 级别
     * @return 不低于最低级别时为真
     */
    public boolean isEnabled(Level level) {
        return level.compareTo(minimum) >= 0;
    }

    /**
     * 写一行日志。
     *
     * @param level 级别
     * @param message 文案
     * @param fields 本行的属性
     */
    public void log(Level level, String message, Map<String, ?> fields) {
        log(level, message, null, fields);
    }

    /**
     * 写一行带异常的日志：异常的堆栈写进属性 {@code exception}，仍是一行。
     *
     * @param level 级别
     * @param message 文案
     * @param thrown 异常；没有时为 {@code null}
     * @param fields 本行的属性
     */
    public void log(Level level, String message, Throwable thrown, Map<String, ?> fields) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(fields, "fields");
        if (!isEnabled(level)) {
            return;
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        try (JsonGenerator generator = JSON.createGenerator(line)) {
            generator.writeStartObject();
            generator.writeStringField("level", level.wireName());
            generator.writeStringField("message", message);
            for (Map.Entry<String, Object> entry : scope.entrySet()) {
                writeField(generator, entry.getKey(), entry.getValue());
            }
            // 本行的属性按名字排序写出：同样的日志总是同样的一行，便于比对
            for (Map.Entry<String, ?> entry : new TreeMap<>(fields).entrySet()) {
                writeField(generator, entry.getKey(), entry.getValue());
            }
            if (thrown != null) {
                StringWriter trace = new StringWriter();
                thrown.printStackTrace(new PrintWriter(trace));
                generator.writeStringField("exception", trace.toString());
            }
            generator.writeEndObject();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        line.write('\n');
        sink.write(line.toByteArray());
    }

    /**
     * 写一行信息日志。
     *
     * @param message 文案
     * @param fields 本行的属性
     */
    public void information(String message, Map<String, ?> fields) {
        log(Level.INFORMATION, message, fields);
    }

    /**
     * 写一行告警日志。
     *
     * @param message 文案
     * @param fields 本行的属性
     */
    public void warning(String message, Map<String, ?> fields) {
        log(Level.WARNING, message, fields);
    }

    /**
     * 写一行错误日志。
     *
     * @param message 文案
     * @param thrown 异常；没有时为 {@code null}
     * @param fields 本行的属性
     */
    public void error(String message, Throwable thrown, Map<String, ?> fields) {
        log(Level.ERROR, message, thrown, fields);
    }

    /**
     * 把 {@code java.util.logging} 的根日志改写到本日志：gRPC 与 {@code System.Logger} 缺省都经它写 stderr，
     * 它自带的格式一条占两行以上，宿主会拆成几条；去掉根日志上的控制台输出，改为一行一个 JSON 对象。
     */
    void bridgePlatformLogging() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof java.util.logging.ConsoleHandler) {
                root.removeHandler(handler);
            }
        }
        root.addHandler(new PlatformHandler(this));
    }

    private static void writeField(JsonGenerator generator, String name, Object value) throws IOException {
        // level 与 message 是协议的成员，作用域属性不能覆盖它们
        if ("level".equals(name) || "message".equals(name)) {
            return;
        }
        generator.writeFieldName(name);
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof Boolean bool) {
            generator.writeBoolean(bool);
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            generator.writeNumber(((Number) value).longValue());
        } else {
            generator.writeString(value.toString());
        }
    }

    /** 把 java.util.logging 的级别映射到协议级别。 */
    private static Level levelOf(java.util.logging.Level level) {
        int value = level.intValue();
        if (value >= java.util.logging.Level.SEVERE.intValue()) {
            return Level.ERROR;
        }
        if (value >= java.util.logging.Level.WARNING.intValue()) {
            return Level.WARNING;
        }
        if (value >= java.util.logging.Level.CONFIG.intValue()) {
            return Level.INFORMATION;
        }
        if (value >= java.util.logging.Level.FINE.intValue()) {
            return Level.DEBUG;
        }
        return Level.TRACE;
    }

    /** 同一个输出上的整行写出。 */
    private static final class Sink {
        private final OutputStream target;

        /** 一行在锁里写完并刷新；用 ReentrantLock 而不是 synchronized，虚拟线程在这里阻塞也不钉住载体线程。 */
        private final ReentrantLock lock = new ReentrantLock();

        Sink(OutputStream target) {
            this.target = target;
        }

        void write(byte[] line) {
            lock.lock();
            try {
                target.write(line);
                target.flush();
            } catch (IOException ignored) {
                // stderr 已关闭（宿主已退出）：日志无处可写，丢弃，不影响调用
            } finally {
                lock.unlock();
            }
        }
    }

    /** java.util.logging 的处理器：每条记录写成一行。 */
    private static final class PlatformHandler extends Handler {
        private final PluginLogger logger;

        /** 只用来展开记录里的参数。 */
        private final SimpleFormatter formatter = new SimpleFormatter();

        PlatformHandler(PluginLogger logger) {
            this.logger = logger;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            if (record.getLoggerName() != null) {
                fields.put("logger", record.getLoggerName());
            }
            String message = formatter.formatMessage(record);
            logger.log(levelOf(record.getLevel()), message == null ? "" : message, record.getThrown(), fields);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
