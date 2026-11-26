package com.xihanfun.framework.invocation;

import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次调用的上下文（RFC-0003）：取消与截止时间、W3C 追踪、Baggage、请求作用域对象袋与按派生传递的值。
 *
 * <p>不可变、线程安全：派生方法返回新实例，可以在虚拟线程之间传递与并发读取。派生的上下文共享对象袋（{@link #items()}）与取消状态，
 * Baggage 与值按派生复制；{@link #withCancellation()}、{@link #withDeadline(Instant)}、{@link #withTimeout(Duration)}
 * 派生出新的取消状态，父级取消时连带取消，反之不影响父级。
 *
 * <p>取消的归因只看令牌（RFC-0003 规则 1、RFC-0004）：截止令牌已触发即 {@code KERNEL_TIMEOUT_001}，不论调用方是否也已取消；
 * 只有调用方取消时为 {@code KERNEL_CANCELLED_001}。
 */
public final class Context {
    /** Baggage 最多的条目数，与 W3C Baggage 的成员上限一致。 */
    public static final int BAGGAGE_CAPACITY = 180;

    /** 按派生传递的值最多的不同键数。 */
    public static final int VALUE_CAPACITY = 256;

    private final CancellationState cancellation;
    private final String traceId;
    private final String spanId;

    /** 上游给出的采样位；没有上游（这次调用是链路的根）为 {@code null}。 */
    private final Boolean sampled;

    /** 只读的 Baggage。 */
    private final Map<String, String> baggage;

    /** 派生上下文共享的对象袋。 */
    private final Items items;

    /** 只读的按派生传递的值。 */
    private final Map<ContextKey<?>, Object> values;

    private Context(CancellationState cancellation, String traceId, String spanId, Boolean sampled, Map<String, String> baggage, Items items,
            Map<ContextKey<?>, Object> values) {
        this.cancellation = cancellation;
        this.traceId = traceId;
        this.spanId = spanId;
        this.sampled = sampled;
        this.baggage = baggage;
        this.items = items;
        this.values = values;
    }

    /**
     * 创建没有截止时间、不会被取消的根上下文，生成新的追踪。
     *
     * @return 上下文
     */
    public static Context background() {
        return builder().build();
    }

    /**
     * 创建根上下文的构建器：协议适配器与插件运行时用它从载体还原截止时间、追踪与 Baggage。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    // ---- 取消与截止时间 ----

    /**
     * 返回生效的截止时间（UTC）：自己的与祖先的取较早者。
     *
     * @return 截止时间；没有时为空
     */
    public Optional<Instant> deadline() {
        return cancellation.deadline();
    }

    /**
     * 判断是否有截止时间。
     *
     * @return 有截止时间时为真
     */
    public boolean hasDeadline() {
        return cancellation.deadline().isPresent();
    }

    /**
     * 按给定时刻判断是否已过截止时间；只用于展示与估算，取消的归因看 {@link #cancellationError()}。
     *
     * @param now 当前时刻
     * @return 有截止时间且不早于它时为真
     */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return cancellation.deadline().map(deadline -> !now.isBefore(deadline)).orElse(false);
    }

    /**
     * 返回到截止时间的剩余时间。
     *
     * @param now 当前时刻
     * @return 剩余时间，已过期为零；没有截止时间时为空
     */
    public Optional<Duration> remainingTime(Instant now) {
        Objects.requireNonNull(now, "now");
        return cancellation.deadline().map(deadline -> {
            Duration remaining = Duration.between(now, deadline);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        });
    }

    /**
     * 判断调用是否已取消：截止令牌或外部令牌已触发。
     *
     * @return 已取消时为真
     */
    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    /**
     * 把取消与超时翻译成原语层错误。
     *
     * <p>建上下文时不判断「开始前已超时」：那由调用器在 Resilience 阶段的内置步骤里按时钟核对（RFC-0011 规则 10，ADR-0236）。
     *
     * @return 截止令牌已触发为 {@code KERNEL_TIMEOUT_001} + {@value InvocationMessages#DEADLINE_EXCEEDED}，只有外部取消为
     *     {@code KERNEL_CANCELLED_001}；未取消时为空
     */
    public Optional<Error> cancellationError() {
        if (cancellation.isDeadlineFired()) {
            return Optional.of(InvocationErrors.deadlineExceeded());
        }
        if (cancellation.isCancelledExternally()) {
            return Optional.of(InvocationErrors.cancelled());
        }
        return Optional.empty();
    }

    /**
     * 把取消与超时翻译成原语层错误。
     *
     * @return 同 {@link #cancellationError()}
     * @deprecated 用 {@link #cancellationError()}：返回的是错误，不是取消信号；其他语言的 SDK 里同名的成员是取消信号对象，
     *     错误叫 {@code cancellationError}。返回值相同。
     */
    @Deprecated
    public Optional<Error> cancellation() {
        return cancellationError();
    }

    /**
     * 登记取消回调：已经取消时立即在当前线程执行，否则在触发取消的线程上执行一次（截止时间到达时是一个新的虚拟线程）。
     *
     * <p>回调要短、不阻塞，例如取消下游的 gRPC 调用；用完关闭返回的登记，免得它留在长寿命的父级上。
     *
     * @param action 回调
     * @return 登记；关闭即撤销
     */
    public Registration onCancelled(Runnable action) {
        return cancellation.onCancelled(Objects.requireNonNull(action, "action"));
    }

    /**
     * 阻塞当前线程直到调用被取消或等待超时；在虚拟线程上调用不占用平台线程。
     *
     * @param timeout 最长等待时间
     * @return 已取消时为真，等待超时为假
     * @throws InterruptedException 等待时被中断
     */
    public boolean awaitCancellation(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException tooLong) {
            // 超出纳秒计数的等待按无限长处理
            nanos = Long.MAX_VALUE;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Registration registration = onCancelled(latch::countDown);
        try {
            return latch.await(nanos, TimeUnit.NANOSECONDS);
        } finally {
            registration.close();
        }
    }

    /**
     * 派生一个可以由调用方取消的上下文。
     *
     * @return 可取消的上下文；用完必须取消（try-with-resources）
     */
    public CancellableContext withCancellation() {
        return scope(CancellationState.child(cancellation, null));
    }

    /**
     * 派生一个带截止时间的、可以由调用方取消的上下文；下游的截止时间不晚于上游（RFC-0003 规则 2），所以生效的是两者中较早的。
     *
     * @param deadline 截止时间
     * @return 可取消的上下文；用完必须取消（try-with-resources）
     */
    public CancellableContext withDeadline(Instant deadline) {
        return scope(CancellationState.child(cancellation, Objects.requireNonNull(deadline, "deadline")));
    }

    /**
     * 以相对时间派生带截止时间的、可以由调用方取消的上下文。
     *
     * @param timeout 从现在起的时长
     * @return 可取消的上下文；用完必须取消（try-with-resources）
     */
    public CancellableContext withTimeout(Duration timeout) {
        return withDeadline(Instant.now().plus(Objects.requireNonNull(timeout, "timeout")));
    }

    // ---- 追踪 ----

    /**
     * 返回追踪 ID。
     *
     * @return 32 位小写十六进制
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 返回 Span ID（W3C parent-id）。
     *
     * @return 16 位小写十六进制
     */
    public String spanId() {
        return spanId;
    }

    /**
     * 返回上游的采样决定：trace-flags 的最低位，其余位不进上下文。
     *
     * @return 是否采样；没有上游、这次调用是链路的根时为空
     */
    public Optional<Boolean> sampled() {
        return Optional.ofNullable(sampled);
    }

    /**
     * 返回传给下游的 W3C traceparent（RFC-0003 规则 7「写出」）：版本 {@code 00}，标志只写采样位；上游的 {@code 00} 原样传下去，
     * 标志为空（链路的根）时本 SDK 没有采样器，写 {@code 01}，按已采样传下去、由下游的采样器决定。
     *
     * @return traceparent 文本
     */
    public String traceParent() {
        return new TraceParent(traceId, spanId, sampled == null || sampled).toString();
    }

    // ---- Baggage ----

    /**
     * 返回 Baggage。
     *
     * @return 只读表
     */
    public Map<String, String> baggage() {
        return baggage;
    }

    /**
     * 取一个 Baggage 值。
     *
     * @param key 键
     * @return 值；没有时为空
     */
    public Optional<String> findBaggage(String key) {
        return Optional.ofNullable(baggage.get(Objects.requireNonNull(key, "key")));
    }

    /**
     * 派生一个多一个 Baggage 键值的上下文；格式合法但未登记的 {@code xihan.<name>} 键只在进程内有效，出进程时剥离。
     *
     * <p>程序内逐项派生给已登记的键一个不可接受的值（{@link BaggageKeys#isAcceptableValue(String)}：空、超过 256 个 UTF-16 码元、含控制字符或孤立的代理项）
     * 是编程错误，抛出 {@link IllegalArgumentException}，与键不合文法时一样，不静默丢弃也不保留原值
     * （conventions/baggage-keys.md，ADR-0235）；{@code xihan.idempotency-key} 例外，原样保留，由 idempotency 阶段以校验错误拒绝。
     * 外部输入经 {@link Builder#baggage(String, String)} 进入，那里丢弃不合的成员。
     *
     * @param key 键，必须符合 {@code xihan.<name>}
     * @param value 值
     * @return 新的上下文
     * @throws IllegalArgumentException 键不合格式，或已登记键的值不可接受
     * @throws IllegalStateException Baggage 已满
     */
    public Context withBaggage(String key, String value) {
        requireBaggageKey(key);
        Objects.requireNonNull(value, "value");
        if (!admits(key, value)) {
            throw new IllegalArgumentException("The value of the baggage key '" + key + "' must be non-empty, at most "
                    + BaggageKeys.MAX_VALUE_LENGTH + " UTF-16 code units and free of control characters.");
        }
        Map<String, String> copy = new TreeMap<>(baggage);
        copy.put(key, value);
        requireBaggageCapacity(copy.size());
        return new Context(cancellation, traceId, spanId, sampled, Collections.unmodifiableMap(copy), items, values);
    }

    /**
     * 派生一个只保留已登记键的上下文：出进程的载体只写已登记的键（RFC-0003 规则 3）。
     *
     * @return 新的上下文
     */
    public Context withoutUnregisteredBaggage() {
        Map<String, String> copy = new TreeMap<>();
        baggage.forEach((key, value) -> {
            if (BaggageKeys.isRegistered(key)) {
                copy.put(key, value);
            }
        });
        return new Context(cancellation, traceId, spanId, sampled, Collections.unmodifiableMap(copy), items, values);
    }

    /**
     * 返回当前租户 ID。
     *
     * @return 租户 ID；没有时为空
     */
    public Optional<String> tenant() {
        return findBaggage(BaggageKeys.TENANT);
    }

    /**
     * 返回当前主体 ID。
     *
     * @return 主体 ID；匿名时为空
     */
    public Optional<String> principal() {
        return findBaggage(BaggageKeys.PRINCIPAL);
    }

    /**
     * 返回业务关联 ID。
     *
     * @return 关联 ID；没有时为空
     */
    public Optional<String> correlation() {
        return findBaggage(BaggageKeys.CORRELATION);
    }

    /**
     * 返回幂等键。
     *
     * @return 幂等键；没有时为空
     */
    public Optional<String> idempotencyKey() {
        return findBaggage(BaggageKeys.IDEMPOTENCY_KEY);
    }

    /**
     * 返回请求文化（BCP 47）。
     *
     * @return 区域名；没有时为空
     */
    public Optional<String> locale() {
        return findBaggage(BaggageKeys.LOCALE);
    }

    // ---- 对象袋与值 ----

    /**
     * 返回请求作用域对象袋：派生上下文共享，不跨进程传播。
     *
     * @return 对象袋
     */
    public Items items() {
        return items;
    }

    /**
     * 派生一个带一个值的上下文：只有这个上下文与从它派生的上下文看得到，同一次调用的其他分支看不到。
     *
     * <p>适合在一次调用里嵌套另一次调用时各自携带自己的状态，例如当前操作的描述。
     *
     * @param key 键
     * @param value 值
     * @param <T> 值的类型
     * @return 新的上下文
     * @throws IllegalStateException 不同的键超过 {@value #VALUE_CAPACITY} 个
     */
    public <T> Context withValue(ContextKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<ContextKey<?>, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        // 同一个键再派生是替换，表的大小只随不同的键增长
        if (copy.size() > VALUE_CAPACITY) {
            throw new IllegalStateException("An invocation context cannot carry more than " + VALUE_CAPACITY + " values.");
        }
        return new Context(cancellation, traceId, spanId, sampled, baggage, items, Collections.unmodifiableMap(copy));
    }

    /**
     * 取一个按派生传递的值。
     *
     * @param key 键
     * @param <T> 值的类型
     * @return 值；没有时为空
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> findValue(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 值只能经同一个类型化的键放入，转换是安全的
        return Optional.ofNullable((T) values.get(key));
    }

    /**
     * 返回便于日志的文本：追踪、截止时间与 Baggage 的键，不含 Baggage 的值。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "Context[traceparent=" + traceParent() + ", deadline=" + deadline().map(Instant::toString).orElse("none") + ", baggage="
                + baggage.keySet() + ", cancelled=" + isCancelled() + "]";
    }

    private CancellableContext scope(CancellationState state) {
        return new CancellableContext(new Context(state, traceId, spanId, sampled, baggage, items, values), state);
    }

    /**
     * 判断一个成员能否进入上下文：外部输入在入口丢弃不合的成员，程序内派生不合即报错，上下文只携带已经通过的值
     * （conventions/baggage-keys.md）。约束只管已登记的键；幂等键两种情形都例外，丢了它重复的调用会执行两次。
     */
    private static boolean admits(String key, String value) {
        return !BaggageKeys.isRegistered(key) || BaggageKeys.IDEMPOTENCY_KEY.equals(key) || BaggageKeys.isAcceptableValue(value);
    }

    private static void requireBaggageKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!BaggageKeys.isWellFormed(key)) {
            throw new IllegalArgumentException("The baggage key '" + key + "' must match xihan.<name>.");
        }
    }

    private static void requireBaggageCapacity(int size) {
        if (size > BAGGAGE_CAPACITY) {
            throw new IllegalStateException("The baggage cannot hold more than " + BAGGAGE_CAPACITY + " entries.");
        }
    }

    /**
     * 根上下文的构建器；不是线程安全的。
     *
     * <p>没有给出追踪时生成新的追踪 ID 与 Span ID，采样标志为空（链路的根）；只给追踪 ID 不给标志按上游已采样处理（RFC-0003 规则 7）。
     */
    public static final class Builder {
        private Instant deadline;
        private String traceId;
        private String spanId;
        private Boolean sampled;
        private final Map<String, String> baggage = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * 设置绝对截止时间（UTC）。
         *
         * @param value 截止时间
         * @return 本构建器
         */
        public Builder deadline(Instant value) {
            this.deadline = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 以从现在起的时长设置截止时间。
         *
         * @param value 时长
         * @return 本构建器
         */
        public Builder timeout(Duration value) {
            this.deadline = Instant.now().plus(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * 设置追踪 ID 与 Span ID，按上游已采样处理。
         *
         * @param traceIdValue 32 位小写十六进制
         * @param spanIdValue 16 位小写十六进制
         * @return 本构建器
         * @throws IllegalArgumentException ID 不合格式或全为零
         */
        public Builder trace(String traceIdValue, String spanIdValue) {
            TraceParent parsed = new TraceParent(traceIdValue, spanIdValue, true);
            this.traceId = parsed.traceId();
            this.spanId = parsed.spanId();
            this.sampled = true;
            return this;
        }

        /**
         * 从 W3C traceparent 设置追踪与采样标志；文本不合法时按 W3C 的要求忽略，等同于没有上游。
         *
         * @param value traceparent 文本
         * @return 本构建器
         */
        public Builder traceParent(String value) {
            TraceParent.parse(value).ifPresent(parsed -> {
                this.traceId = parsed.traceId();
                this.spanId = parsed.spanId();
                this.sampled = parsed.sampled();
            });
            return this;
        }

        /**
         * 设置一个 Baggage 键值：这是外部输入（载体、插件元数据）进入上下文的入口。
         *
         * <p>已登记键的值不可接受时丢弃这个成员，已经设置的同名值保留（与 W3C Baggage 忽略不合法的成员一致，ADR-0235）；
         * {@code xihan.idempotency-key} 除外。键不合文法仍是编程错误。
         *
         * @param key 键，必须符合 {@code xihan.<name>}
         * @param value 值
         * @return 本构建器
         * @throws IllegalArgumentException 键不合格式
         * @throws IllegalStateException Baggage 已满
         */
        public Builder baggage(String key, String value) {
            requireBaggageKey(key);
            Objects.requireNonNull(value, "value");
            if (admits(key, value)) {
                baggage.put(key, value);
                requireBaggageCapacity(baggage.size());
            }
            return this;
        }

        /**
         * 设置多个 Baggage 键值。
         *
         * @param values 键值
         * @return 本构建器
         * @throws IllegalArgumentException 有键不合格式
         * @throws IllegalStateException Baggage 已满
         */
        public Builder baggage(Map<String, String> values) {
            Objects.requireNonNull(values, "values").forEach(this::baggage);
            return this;
        }

        /**
         * 构建根上下文；有截止时间时起定时器，到点触发截止令牌。
         *
         * @return 上下文
         */
        public Context build() {
            String trace = traceId != null ? traceId : TraceParent.newTraceId();
            String span = spanId != null ? spanId : TraceParent.newSpanId();
            return new Context(CancellationState.root(deadline), trace, span, sampled, Collections.unmodifiableMap(new TreeMap<>(baggage)),
                    new Items(), Map.of());
        }
    }
}
