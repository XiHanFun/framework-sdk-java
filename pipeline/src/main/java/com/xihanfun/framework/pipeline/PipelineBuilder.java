package com.xihanfun.framework.pipeline;

import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 收集中间件与顺序约束，按 RFC-0004 的排序规则排序后构建管道。
 *
 * <p>排序：先按阶段；阶段内先按位置（{@link Placement#BEGINNING} 在前），再按归属的序数字典序，再按登记顺序；
 * 最后按 {@code before} / {@code after} 约束做稳定拓扑排序，约束只调整必要的相对位置，其余保持基准次序。
 *
 * <p>线程安全：全部成员在同一把锁里执行，{@link #describe()}、{@link #dump()} 与 {@link #build(Step)} 反映调用时的登记。
 * 构建出的 {@link Step} 不再随构建器变化。
 */
public final class PipelineBuilder {
    /** 应用自己登记的中间件的归属。 */
    public static final String APP_OWNER = "app";

    /** 一个管道最多的中间件数。 */
    public static final int CAPACITY = 1024;

    /** 一个管道最多的顺序约束数。 */
    public static final int CONSTRAINT_CAPACITY = 4096;

    /** 打印时阶段名所占的列宽（不含其后的空格）：阶段名左对齐，加一个空格共 13 列。 */
    private static final int STAGE_COLUMN = 12;

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();

    /** 登记序号，单调递增。 */
    private int sequence;

    /**
     * 创建空的构建器。
     */
    public PipelineBuilder() {
    }

    /**
     * 复制一份构建器：登记、按名字的约束与登记顺序都相同，此后两份互不影响。调用器用它在使用者的管道上加内置步骤，不改使用者的构建器。
     *
     * @return 新的构建器
     */
    public PipelineBuilder copy() {
        PipelineBuilder copy = new PipelineBuilder();
        lock.lock();
        try {
            copy.registrations.addAll(registrations);
            copy.constraints.addAll(constraints);
            copy.sequence = sequence;
        } finally {
            lock.unlock();
        }
        return copy;
    }

    /**
     * 按清单里的阶段名解析阶段。
     *
     * @param name 阶段名，如 {@code observe}
     * @return 阶段
     * @throws FrameworkException 阶段名未知（{@code KERNEL_PIPELINE_002}）
     */
    public static PipelineStage parseStage(String name) {
        Objects.requireNonNull(name, "name");
        return PipelineStage.parse(name)
                .orElseThrow(() -> new FrameworkException(PipelineErrors.UNKNOWN_STAGE, "'" + name + "' is not a pipeline stage."));
    }

    /**
     * 登记中间件到 {@code transaction} 阶段末尾，归属 {@value #APP_OWNER}（RFC-0004 排序规则 1）。
     *
     * @param middleware 中间件
     * @return 本构建器
     * @throws FrameworkException 同名中间件已登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder use(Middleware middleware) {
        return useAt(middleware, PipelineStage.TRANSACTION, Placement.END, APP_OWNER);
    }

    /**
     * 登记中间件到指定阶段的末尾，归属 {@value #APP_OWNER}。
     *
     * @param middleware 中间件
     * @param stage 阶段
     * @return 本构建器
     * @throws FrameworkException 同名中间件已登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder useAt(Middleware middleware, PipelineStage stage) {
        return useAt(middleware, stage, Placement.END, APP_OWNER);
    }

    /**
     * 登记中间件到指定阶段与位置，归属 {@value #APP_OWNER}。
     *
     * @param middleware 中间件
     * @param stage 阶段
     * @param placement 阶段内位置
     * @return 本构建器
     * @throws FrameworkException 同名中间件已登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder useAt(Middleware middleware, PipelineStage stage, Placement placement) {
        return useAt(middleware, stage, placement, APP_OWNER);
    }

    /**
     * 登记中间件到指定阶段与位置。
     *
     * @param middleware 中间件
     * @param stage 阶段
     * @param placement 阶段内位置
     * @param owner 归属：清单 ID 或 {@value #APP_OWNER}
     * @return 本构建器
     * @throws FrameworkException 同名中间件已登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder useAt(Middleware middleware, PipelineStage stage, Placement placement, String owner) {
        return add(middleware, null, stage, placement, owner);
    }

    /**
     * 按名字登记同一类型的另一个实例（排序规则 7）：名字在类型内唯一，打印为 {@code 类型名[名字]}。
     *
     * @param instance 实例名，不能为空串
     * @param middleware 中间件
     * @param stage 阶段
     * @param placement 阶段内位置
     * @param owner 归属：清单 ID 或 {@value #APP_OWNER}
     * @return 本构建器
     * @throws FrameworkException 同一类型的同名实例已登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder useNamedInstance(String instance, Middleware middleware, PipelineStage stage, Placement placement, String owner) {
        Objects.requireNonNull(instance, "instance");
        if (instance.isEmpty()) {
            throw new IllegalArgumentException("A named middleware instance needs a name.");
        }
        return add(middleware, instance, stage, placement, owner);
    }

    /**
     * 约束 {@code middleware} 在 {@code other} 之前。两端都是中间件名：按名字登记的实例名，或中间件名（类型名）。
     *
     * <p>约束主体在声明时必须已登记；目标在排序时解析，未登记、对应多个中间件或不在同一阶段报 {@code KERNEL_PIPELINE_003}。
     *
     * @param middleware 约束主体
     * @param other 目标
     * @return 本构建器
     * @throws FrameworkException 约束主体未登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder before(String middleware, String other) {
        return constrain(middleware, middleware, other);
    }

    /**
     * 约束 {@code middleware} 在 {@code other} 之后；名字的解析同 {@link #before(String, String)}。
     *
     * @param middleware 约束主体
     * @param other 目标
     * @return 本构建器
     * @throws FrameworkException 约束主体未登记（{@code KERNEL_PIPELINE_003}）
     */
    public PipelineBuilder after(String middleware, String other) {
        return constrain(middleware, other, middleware);
    }

    /**
     * 返回排序后的中间件描述。
     *
     * @return 只读列表
     * @throws FrameworkException 约束成环（{@code KERNEL_PIPELINE_001}）或约束目标不合法（{@code KERNEL_PIPELINE_003}）
     */
    public List<MiddlewareDescriptor> describe() {
        return sorted().stream().map(Registration::descriptor).toList();
    }

    /**
     * 按 {@code xihan pipeline} 的格式打印：一行一个中间件，阶段名左对齐占 13 列，后接打印名；没有中间件的阶段不打印。
     *
     * @return 打印结果，每行以 {@code \n} 结尾
     * @throws FrameworkException 排序失败，同 {@link #describe()}
     */
    public String dump() {
        StringBuilder builder = new StringBuilder();
        for (MiddlewareDescriptor descriptor : describe()) {
            String stage = descriptor.stage().manifestName();
            builder.append(stage).append(" ".repeat(Math.max(0, STAGE_COLUMN - stage.length()))).append(' ').append(descriptor.display()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 构建管道：{@code terminal} 是 {@code handle} 阶段之后的终点（通常是操作分派）。
     *
     * <p>构建出的步骤不可变、线程安全，此后对构建器的改动不影响它。
     *
     * @param terminal 终点
     * @return 管道的入口
     * @throws FrameworkException 排序失败，同 {@link #describe()}
     */
    public Step build(Step terminal) {
        Objects.requireNonNull(terminal, "terminal");
        List<Registration> ordered = sorted();
        Step next = guarded("the terminal step", terminal);
        // 从后往前包：每个中间件持有它的下游，调用时不再查表、不取锁
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Registration registration = ordered.get(index);
            Middleware middleware = registration.middleware();
            Step downstream = next;
            next = guarded("the middleware '" + registration.descriptor().display() + "'", context -> middleware.invoke(context, downstream));
        }
        return next;
    }

    private PipelineBuilder add(Middleware middleware, String instance, PipelineStage stage, Placement placement, String owner) {
        Objects.requireNonNull(middleware, "middleware");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(owner, "owner");
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("A middleware owner must not be empty; use PipelineBuilder.APP_OWNER for the application.");
        }
        String name = middlewareName(middleware);
        MiddlewareDescriptor descriptor = new MiddlewareDescriptor(stage, name, instance, owner, placement);
        lock.lock();
        try {
            // 同一类型的默认实例只登记一次，命名实例的名字在类型内唯一（排序规则 7）：登记时即核对
            for (Registration existing : registrations) {
                if (existing.descriptor().middleware().equals(name) && Objects.equals(existing.descriptor().instance().orElse(null), instance)) {
                    throw registrationError("The middleware '" + descriptor.display() + "' is registered more than once.");
                }
            }
            if (registrations.size() >= CAPACITY) {
                throw registrationError("The pipeline cannot hold more than " + CAPACITY + " middleware.");
            }
            registrations.add(new Registration(middleware, descriptor, sequence++));
        } finally {
            lock.unlock();
        }
        return this;
    }

    private PipelineBuilder constrain(String subject, String first, String second) {
        Objects.requireNonNull(first, "middleware");
        Objects.requireNonNull(second, "other");
        lock.lock();
        try {
            // 约束主体在声明时就解析：未登记或按名字有歧义都立即报（RFC-0004 第 8 条，ADR-0235）；目标留到排序时解析
            List<Registration> subjects = candidates(subject);
            if (subjects.isEmpty()) {
                throw registrationError("The ordering constraint references the unregistered middleware '" + subject + "'.");
            }
            if (subjects.size() > 1) {
                throw registrationError("The middleware name '" + subject + "' matches several registrations; ordering constraints by it are ambiguous.");
            }
            if (constraints.size() >= CONSTRAINT_CAPACITY) {
                throw registrationError("The pipeline cannot hold more than " + CONSTRAINT_CAPACITY + " ordering constraints.");
            }
            constraints.add(new Constraint(first, second));
        } finally {
            lock.unlock();
        }
        return this;
    }

    /**
     * 按排序规则排序：先阶段；阶段内先位置，再归属，再登记顺序，最后按约束做稳定拓扑排序。
     */
    private List<Registration> sorted() {
        lock.lock();
        try {
            List<Registration[]> resolved = new ArrayList<>(constraints.size());
            for (Constraint constraint : constraints) {
                Registration first = single(constraint.first());
                Registration second = single(constraint.second());
                if (first.descriptor().stage() != second.descriptor().stage()) {
                    throw registrationError("The ordering constraint between '" + constraint.first() + "' and '" + constraint.second() + "' crosses stages.");
                }
                resolved.add(new Registration[] {first, second});
            }
            Comparator<Registration> base = Comparator.<Registration, Placement>comparing(registration -> registration.descriptor().placement())
                    .thenComparing(registration -> registration.descriptor().owner())
                    .thenComparingInt(Registration::order);
            List<Registration> result = new ArrayList<>(registrations.size());
            for (PipelineStage stage : PipelineStage.all()) {
                List<Registration> members = new ArrayList<>();
                for (Registration registration : registrations) {
                    if (registration.descriptor().stage() == stage) {
                        members.add(registration);
                    }
                }
                members.sort(base);
                result.addAll(topologicalSort(stage, members, resolved));
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按名字取唯一的登记：名字可以是实例名，也可以是中间件名（类型名）；没有或有多个都是登记错误。
     */
    private Registration single(String name) {
        List<Registration> found = candidates(name);
        if (found.isEmpty()) {
            throw registrationError("The ordering constraint references the unregistered middleware '" + name + "'.");
        }
        if (found.size() > 1) {
            throw registrationError("The middleware name '" + name + "' matches several registrations; ordering constraints by it are ambiguous.");
        }
        return found.get(0);
    }

    private List<Registration> candidates(String name) {
        List<Registration> found = new ArrayList<>();
        for (Registration registration : registrations) {
            MiddlewareDescriptor descriptor = registration.descriptor();
            if (name.equals(descriptor.middleware()) || name.equals(descriptor.instance().orElse(null))) {
                found.add(registration);
            }
        }
        return found;
    }

    /**
     * 在基准次序上应用约束做稳定拓扑排序（Kahn）：每次取基准次序里最靠前的、没有未排前驱的节点，
     * 所以约束只移动必要的节点，其余保持基准次序；没有可取的节点即成环。
     */
    private static List<Registration> topologicalSort(PipelineStage stage, List<Registration> members, List<Registration[]> constraints) {
        Set<Registration> inStage = Collections.newSetFromMap(new IdentityHashMap<>());
        inStage.addAll(members);
        Map<Registration, Integer> incoming = new IdentityHashMap<>();
        Map<Registration, List<Registration>> outgoing = new IdentityHashMap<>();
        for (Registration[] constraint : constraints) {
            Registration first = constraint[0];
            Registration second = constraint[1];
            // 约束已核对为同一阶段：只取本阶段的；出边与入度一一对应，重复声明的约束同样能如实归零
            if (!inStage.contains(first)) {
                continue;
            }
            outgoing.computeIfAbsent(first, key -> new ArrayList<>()).add(second);
            incoming.merge(second, 1, Integer::sum);
        }
        List<Registration> remaining = new ArrayList<>(members);
        List<Registration> ordered = new ArrayList<>(members.size());
        while (!remaining.isEmpty()) {
            int picked = -1;
            for (int index = 0; index < remaining.size(); index++) {
                if (incoming.getOrDefault(remaining.get(index), 0) == 0) {
                    picked = index;
                    break;
                }
            }
            if (picked < 0) {
                throw new FrameworkException(PipelineErrors.CYCLE, "The ordering constraints in stage '" + stage.manifestName() + "' form a cycle.");
            }
            Registration node = remaining.remove(picked);
            ordered.add(node);
            for (Registration successor : outgoing.getOrDefault(node, List.of())) {
                incoming.merge(successor, -1, Integer::sum);
            }
        }
        return ordered;
    }

    /**
     * 取中间件名；lambda 是隐藏类，它的类名每次运行都可能不同，不能当中间件名。
     */
    private static String middlewareName(Middleware middleware) {
        String name = middleware.name();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A middleware needs a name.");
        }
        if (middleware.getClass().isHidden() && name.equals(middleware.getClass().getName())) {
            throw new IllegalArgumentException("A lambda middleware has no stable name; wrap it with Middleware.of(name, ...).");
        }
        return name;
    }

    private static Step guarded(String what, Step step) {
        return context -> {
            Result<Unit> outcome = step.invoke(context);
            if (outcome == null) {
                throw new IllegalStateException("The pipeline step of " + what + " returned no result.");
            }
            return outcome;
        };
    }

    private static FrameworkException registrationError(String message) {
        return new FrameworkException(PipelineErrors.REGISTRATION, message);
    }

    /** 一次登记。 */
    private record Registration(Middleware middleware, MiddlewareDescriptor descriptor, int order) {
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** 一条顺序约束：{@code first} 在 {@code second} 之前，两端都是中间件名。 */
    private record Constraint(String first, String second) {
    }
}
