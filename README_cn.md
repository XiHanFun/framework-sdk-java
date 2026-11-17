<div align="center">

<h1>XiHan Framework SDK for Java</h1>

<h3>与 .NET 宿主相同的原语、清单与管道，由同一套一致性向量验证</h3>

<p>
  <a href="https://github.com/XiHanFun/framework-sdk-java/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/XiHanFun/framework-sdk-java/ci.yml?branch=main&style=flat-square&label=CI" /></a>
  <img alt="JDK 21+" src="https://img.shields.io/badge/JDK-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" />
  <a href="./LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/XiHanFun/framework-sdk-java?style=flat-square&color=green" /></a>
  <a href="https://github.com/XiHanFun/framework-sdk-java/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/XiHanFun/framework-sdk-java?style=flat-square&logo=github&label=Stars&color=1f6feb" /></a>
</p>

<p>
  <a href="https://framework.docs.xihanfun.com"><b>文档</b></a> ·
  <a href="#快速开始"><b>快速开始</b></a> ·
  <a href="https://github.com/XiHanFun/framework-spec"><b>规范</b></a> ·
  <a href="https://github.com/XiHanFun/XiHan.Framework"><b>参考实现</b></a>
</p>

<p><a href="./README.md">English</a> | <b>简体中文</b></p>

</div>

[XiHan Spec](https://github.com/XiHanFun/framework-spec) 的 Java 实现：原语层、清单、调用上下文、管道与操作契约。用它可以把与 .NET 宿主（参考实现 [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework)）相同的原语用在 JVM 应用里。规范以 submodule 放在 `spec/`，一致性由规范仓库的一致性向量验证。

## 模块

整个 SDK 是一个 Maven 构件 `com.xihanfun:xihan-framework`，与其他语言的 SDK 一样只有一个包，JPMS 模块名 `com.xihanfun.framework`，按 Java 包划分：

| Java 包 | 内容 | 规范 |
| --- | --- | --- |
| `com.xihanfun.framework.primitives` | `Error` / `ErrorCode`、`Result<T>`、`Maybe<T>`、`TypedId<Tag, V>`、`LifecycleState`、`PipelineStage`、`Stability`、`BaggageKeys`、消息键与模板（`MessageTemplate`、`MessageCatalog`、`MessageScope`）、`FrameworkException`、`FrameworkInfo` | RFC-0001、RFC-0010、`conventions/` |
| `com.xihanfun.framework.primitives.json` | 线上 JSON 编码：Jackson 模块 `PrimitivesModule` | RFC-0001「线上编码」 |
| `com.xihanfun.framework.manifest` | `xihan.manifest.json` 的模型、按 Schema 严格读取、规范化输出与校验，能力声明与依赖文本 | RFC-0002、`manifest/manifest.schema.json` |
| `com.xihanfun.framework.invocation` | `Context`：取消与截止时间（按令牌归因为 `KERNEL_TIMEOUT_001` / `KERNEL_CANCELLED_001`，可登记回调）、W3C 追踪、已登记的 Baggage、对象袋 `Items`、按派生传递的值 | RFC-0003 |
| `com.xihanfun.framework.pipeline` | 十一阶段的 `PipelineBuilder`：`use` / `useAt` / `useNamedInstance`、`before` / `after`（类型名或实例名）、`describe` / `dump`、`build`；排序规则与错误码 `KERNEL_PIPELINE_00x` | RFC-0004 |
| `com.xihanfun.framework.operation` | `OperationContract`、`OperationSchema`、`OperationHandler<Req, Res>`、容量 10000 的 `OperationRegistry`、经完整管道执行的 `OperationInvoker`（XGI：JSON 请求与响应，另有类型化入口） | RFC-0011 |

包之间的依赖方向：`operation → pipeline → invocation → primitives`，`manifest → primitives`。外部依赖只有 `jackson-databind`，模块描述里是 `requires transitive`。

## 快速开始

需要 JDK 21+。Maven 坐标 `com.xihanfun:xihan-framework`（Maven Central 命名空间 `com.xihanfun` 已验证，尚未发布）：

```xml
<dependency>
  <groupId>com.xihanfun</groupId>
  <artifactId>xihan-framework</artifactId>
  <version>${xihan-framework.version}</version>
</dependency>
```

在 Java 应用里用原语：

```java
record CreateOrder(int quantity) { }
record OrderCreated(String id, int quantity) { }

OperationRegistry registry = new OperationRegistry();
registry.add(OperationContract.of("orders.create").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP),
        CreateOrder.class, OrderCreated.class,
        (context, request) -> request.quantity() > 0
                ? Result.ok(new OrderCreated("o-" + context.correlation().orElse(""), request.quantity()))
                : Result.fail(Error.validation("ORDERS_VALIDATION_001", "orders.quantity.invalid", Map.of("quantity", request.quantity()))));

PipelineBuilder pipeline = new PipelineBuilder()
        .useAt(Middleware.of("Observe", (context, next) -> next.invoke(context)), PipelineStage.OBSERVE, Placement.END, PipelineBuilder.APP_OWNER);
OperationInvoker invoker = new OperationInvoker(registry, pipeline);

Context context = Context.builder().timeout(Duration.ofSeconds(2)).baggage(BaggageKeys.CORRELATION, "c1").build();
OperationResponse response = invoker.invoke(context, OperationRequest.of("orders.create", "{\"quantity\":2}"));
// response.payloadText() == Optional[{"id":"o-c1","quantity":2}]
```

- 可恢复失败返回 `Result` / `Error`，不抛异常。错误码按 `<领域大写>_<KIND>_<NNN>`，文案用消息键 + 语言文件（RFC-0010），`Error.message()` 在没有已解析文案时按 `MessageScope` 的进程默认目录解析，翻译不出时用 SDK 自带的内核消息英文模板（`KernelMessages`，与 `kernel-messages.json` 逐字相同），再退回 `键 (参数)` 的描述。
- 配置错误（清单不合法、管道成环、操作重复登记、登记表已满）抛出带错误码的 `FrameworkException`；参数格式不合法这类调用方的编程错误抛 `IllegalArgumentException`，状态不允许抛 `IllegalStateException`。消息是英文句子，标识符加单引号。
- `Result<T>`、`Error`、`Maybe<T>`、`TypedId` 的 JSON 编码按 RFC-0001「Result」：`new ObjectMapper().registerModule(new PrimitivesModule())`；从线上收到的文本用 `PrimitivesJson.read(mapper, bytes, type)` 读，它先过共同的 JSON 文本检查。生命周期迁移表、管道阶段顺序、错误码、Baggage 键与值、能力依赖文法、JSON 文本检查、线上编码、消息模板、内核错误、清单的读取与写出、载荷解码与管道排序都由 `spec/conformance/vectors` 验证，期望值逐字相等。

## 与宿主共用的行为

按规范 ADR-0233、ADR-0235、ADR-0236 统一的行为，各语言 SDK 与 .NET 相同：

- **JSON 文本**：Result 与 Error 的线上编码、操作载荷与清单先过同一遍检查（`JsonText.check`，向量 `json-text.json`）：语法按 RFC 8259（无注释、尾逗号、`NaN`、前导零、正号、多根值，空文本与只有空白也不合法），文本是 UTF-8、开头的 BOM 是语法错误；嵌套不超过 64 层；字符串与成员名必须是合法 Unicode（拒绝非法 UTF-8、过长编码、编码成 UTF-8 的代理项、原始控制字符、非法转义、孤立的代理项转义）；任何一层都不能有重复成员（按转义还原后比较）。语法错误优先，位置只写 `$`；否则写第一处重复的路径。Jackson 会跳过 BOM、按字节猜 UTF-16、放过孤立的代理项，所以这一遍不经 Jackson。
- **线上编码**：无值成功（`Result<Unit>`）只写 `{"ok":true}`；`Result<Maybe<T>>` 的无值是 `"value":null`。`Error` 的 `message` 必填且非空；`messageKey`、`details`、`inner` 缺失表示没有，写成 `null` 不合法；未知成员忽略。重复成员在任何一层都不合法，含 `value`、`details` 与 `inner` 的内部（上一版不查 `value` 里面）；解码器自己核对重复、64 层与孤立的代理项，不看 `ObjectMapper` 的配置。数字按值模型：64 位范围内的整数写法原样往返（9007199254740993 不会变成 9007199254740992），其余是双精度浮点；细节里整数值的浮点写成 `2.0`、`-0.0`、`1.0E17`，非有限的写成字符串 `"NaN"`、`"Infinity"`、`"-Infinity"`（以前细节里不许放非有限值）。
- **模板**：消息键最长 128 个字符。说明符只认 `^[DdFfNnXx][0-9]{0,2}$`：`D` 与 `X` 只用于整数，`X` 只用于非负整数，`F` 与 `N` 用于整数与浮点，不适用或不认的（`E2`、`G`、`0.00`、`yyyy`、`D100`、带空格）一律按无格式写出（上一版精度可到 99）。`F` 与 `N` 按精确十进制展开、正好一半取偶（0.125 写 `F2` 得 `0.12`，2.675 写 `F2` 得 `2.67`），写出全部整数位，负数舍入成零保留负号。数组与对象写成紧凑 JSON，对象成员按名字的 UTF-16 码元序排列，超过 64 层的容器写 `null`（上一版用 Java 的 `toString`）。浮点 1e-4 ≤ |x| < 1e17 不用指数，范围外写 `1E+17`、`9.9E-05`、`5E-324`，负零 `-0`。`Describe` 的参数按名字的序数顺序。
- **区域**：`MessageTemplate.format(template, arguments, locale)` / `tryFormat(…, locale)` / `text(value, locale)` 与 `MessageCatalog.resolve(key, locale, arguments)` 按区域取小数点与分组符：de-DE、es-ES、pt-BR 是 `,` 与 `.`，fr-FR 是 `,` 与 U+202F，ru-RU 是 `,` 与 U+00A0，其余区域与不给区域是 `.` 与 `,`；区域名不区分大小写。小数点用在无格式的浮点（含指数的尾数，de-DE 的 1.5e20 写 `1,5E+20`）与 `F`、`N` 上，分组符只用在 `N` 上；整数无格式时不分组，`D`、`X` 与数组、对象里的数字不随区域变化。`Error.message()` 按 `MessageScope` 的进程默认区域取符号，回退到内置 en-US 模板时也一样。
- **内核错误**：错误码、消息键与参数照 `kernel-messages.json`，内置的英文模板与它逐字相同，含清单读取的 9 个键，所以清单读取失败的 `message` 是与 .NET 相同的英文句子；`kernel.operation.type_mismatch` 只带 `id`（上一版带 Java 的类型名 `expected` / `actual`）。配置期的重复登记与登记表已满仍抛 `FrameworkException`，`toError()` 交出带键与参数的错误。
- **调用器的内置步骤**：位置与 .NET 的内置中间件相同，都按归属 `xihan.kernel.core`（`OperationInvoker.KERNEL_OWNER`）放在所在阶段的 `Beginning`，与同位置的其他中间件按归属的序数序、同归属按登记顺序排列（内置步骤登记在使用者的中间件之后）。Observe 阶段翻译异常（`KERNEL_UNEXPECTED_001` + `kernel.invocation.unhandled_exception`，`FrameworkException` 的错误进 `inner`，取消引起的异常按令牌归因，`java.lang.Error` 不翻译），只接住排在它之后的异常：Ingress 阶段与 Observe 阶段 `Beginning` 上归属排在它之前（如 `app`）的中间件抛出的异常原样交给调用方。Resilience 阶段核对截止时间，进入时已到即 `KERNEL_TIMEOUT_001` + `kernel.invocation.deadline_before_start`（上一版在第一个中间件之前核对，已撤回）。Validate 阶段解码原始调用的载荷，类型化调用跳过。进入管道前只核对未登记（`KERNEL_NOTFOUND_001`）与类型化调用的类型不符（`KERNEL_VALIDATION_002`），按这个顺序。调用器复制使用者的 `PipelineBuilder`（`copy()`）再加内置步骤，不改动它。
- **响应的编码**：XGI 入口在管道结束之后才把响应编码成载荷，中间件看到的是编码之前的结果；`Operations.findResponse(context)` 给出 handle 阶段执行成功的响应对象（上一版的 `findResponsePayload` 给载荷，已撤回）。写不出时（类型化的浮点成员是 NaN 或无穷、序列化不支持的值）以 `KERNEL_UNEXPECTED_001` + `kernel.invocation.unhandled_exception` 结束，异常链上有 `FrameworkException` 时它的错误进 `inner`，不抛给调用方，没有载荷（上一版是 `Error.literal` 的英文消息）。NaN 不写成字符串：编码时关掉 Jackson 的 `WRITE_NAN_AS_STRINGS`，写出的文本再过共同的 JSON 文本检查。类型化入口不编码。
- **取消只经令牌**：调用器与内置步骤不中断线程、不取消 future，只让上下文的令牌触发；令牌触发之后处理器照常返回结果的，调用以那个结果结束。处理器要尽早结束时自己看 `context.cancellation()`、`onCancelled` 或 `awaitCancellation`。
- **载荷解码**：先过共同检查（语法错误写 `$`，重复成员写第一处重复的路径，先于一切类型问题）；空载荷等价于 `{}`，只有空白是语法错误（上一版当作 `{}`）；然后按请求类型以文本顺序核对（`RequestShape`，不靠 Jackson 的读取顺序）：成员名区分大小写、未知成员忽略、数字不从字符串读、整数不接受 `1.0` 与 `1e2`、超出类型范围即不符、浮点接受整数写法且 1e400 上溢为无穷、1e-400 为 0；必填成员缺少时在所在对象结束时报该对象的路径。载荷的文本按 JSON 值比较，空白、转义与数字的写法不是契约；任意 JSON 类型的成员（`JsonNode`、`Object`）原样保留读到的值：超出双精度的整数精确，`2.0` 与 `2`、`-0.0` 与 `0` 分得开，`1e400`、`1e-400` 这类双精度表示不了的数保留原文的精确十进制，写回时 JSON 值相等，不变成 `null`、无穷或 0。`reason` 只写路径：载荷是 `null`、不是对象或根上就不合时写 `$`；成员名含 `.`、空格、`'`、`/`、`"`、`[`、`]`、`(`、`)`、`\`、制表、换行、回车、换页、退格、U+0085、U+2028、U+2029 时写 `$['名字']`，其余写 `$.名字`。
- **清单的读取**：按 JSON 文本 → 字面量 `null` → 按 Schema 逐项核对 → 跨字段约束的顺序，每一步只报第一处问题，错误码都是 `KERNEL_CAPABILITY_006`（上一版是 `PRIMITIVES_VALIDATION_001` 加英文消息）：`kernel.manifest.invalid_json`（`reason` 是 `$` 或第一处重复的路径）、`kernel.manifest.empty`（根是 `null`，没有参数）、`kernel.manifest.invalid`（`reason` 是第一处问题的路径：成员按文本顺序，每个成员依次查 Schema 之外 → `null` → 类型 → 取值，对象查完后再按 `required` 的顺序查缺少的成员）；跨字段约束按 `primitives.manifest.isolation_required` → `isolation_not_allowed` → `duplicate_provides` → `duplicate_requires` → `duplicate_bindings` 的顺序，参数只有 `id`。`ManifestJson.read(byte[])` 不跳过 BOM；`readFile` 按 UTF-8 读并跳过开头的 UTF-8 BOM，文件不存在为 `kernel.manifest.file_missing`（参数 `path`，以前抛 `IOException`）。接口版本不带前导零、不超过 2147483647，只有依赖的版本可带一个 `^`；中间件名非空，`contributes.health` 按 `^[a-z][a-z0-9.-]*$` 核对，`contributes.permissions` 重复时报第二次出现的项；写了空数组的 `activation` 不合法。非 Wasm 插件贡献中间件交给宿主在发现时拒绝，读取与构建清单不再核对（与 .NET 一致）。`Manifest.Builder.build()` 的跨字段约束带同样的消息键与 `id`，错误码是 `PRIMITIVES_VALIDATION_001`。
- **清单的写出**：`ManifestJson.write` 的排版照 RFC-0002「写出」逐字固定：两个空格缩进、LF、末尾不换行、`": "`；`$schema` 总写 `https://spec.xihanfun.com/manifest/v1.json`，列表总写（空列表写 `[]`），`role` 缺省写 `provider`、`activation` 缺省写 `["onStartup"]`，依赖总写 `optional`、绑定总写 `required`、中间件总写 `before` / `after`，`contributes` 总写；字符串只转义 `"`、`\`、控制字符与孤立的代理项（`<`、`&`、U+2028 与非 ASCII 原样）。上一版省略缺省值并转义 `<>&`。`Manifest` 按生效的值比较（不看 `$schema`，没写的角色、激活事件、贡献点与绑定的 `required` 按缺省），写出的清单再读一遍与原来的相等。
- **文法**：规范的正则都按整串匹配，结尾的换行不例外（`xihan.tenant\n`、带换行的 `traceparent` 不通过）；Java 一律用 `Matcher.matches()`。能力依赖的版本不接受前导零、`+`、空白与超过 2147483647 的值。
- **Baggage 值**：已登记键的值要非空、按 UTF-16 码元计最多 256、不含 U+0000–U+001F 与 U+007F–U+009F。外部输入 `Context.builder().baggage(…)` 丢弃不合的成员、已有的同名值保留；程序内派生 `withBaggage` 给已登记的键一个不合的值抛 `IllegalArgumentException`（上一版是去掉这个键）。`xihan.idempotency-key` 两种情形都例外。
- **追踪**：`traceparent` 只认 `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$`、标识不能全零，不合法的当作没给；只取采样位，写出只写 `00` / `01`（上一版整字节保留标志、有 `traceFlags()`，已撤回）；没有上游时写 `01`。
- **管道**：重复登记、未知阶段、约束主体未登记或按名字有歧义在登记时报；约束目标未登记或有歧义、跨阶段与成环在排序（`describe` / `dump` / `build`）时报。排序与 .NET 一样逐个阶段处理：阶段内按基准次序逐个主体解析它的约束（先 before 再 after，各按声明顺序），解析完再排序，所以几处错误同时存在时报的是同一个。按名字的约束与 .NET 同一种匹配：按名字登记的实例只认实例名，没有名字的登记认中间件名（上一版中间件名也能对上按名字登记的实例）。错误消息是 .NET 的英文原文，按名字登记的实例写成 `类型名[名字]`，成环时按基准次序列出全部仍被卡住的中间件，如 `Middleware ordering constraints in stage 'validate' form a cycle: 'Guard[a]', 'Guard[b]'.`。

## 语言映射

Java 的写法与规范的对应：

- **模板参数的值模型**（RFC-0010「模板」）：`null` 是空；`Boolean` 是布尔；`String`、其他 `CharSequence` 与 `Character` 是字符串；`Byte`、`Short`、`Integer`、`Long`、`AtomicInteger`、`AtomicLong` 是 64 位整数，`BigInteger` 在 64 位范围内是整数、超出时是双精度浮点；`Double` 是浮点，`Float` 与 `BigDecimal` 先换成最接近的 `double`（`0.1f` 写 `0.10000000149011612`，`new BigDecimal("2.50")` 写 `2.5`）；键都是 `String` 的 `Map` 是对象，`Collection` 与 Java 数组（含基本类型数组）是数组。模型之外的值（枚举、`UUID`、`java.time` 的类型、键不全是字符串的 `Map` 等）写成 `toString()` 的文本，嵌在数组或对象里时写成这段文本的 JSON 字符串；它们不跨语言，插件要跨语言格式化时先转成字符串。
- **线上编码的数字**：`Error.details()` 里放 `Integer` / `Long` / `BigInteger` / `Float` / `Double` / `BigDecimal` 都可以，写出时按上面的值模型；读回来时 64 位范围内的整数是 `Integer` 或 `Long`，其余是 `Double`，非有限值读回来是字符串。`Result.value` 的内容由值类型自己的 Jackson 配置写出；读取时超出 64 位的整数按值模型先成为浮点，再交给值类型。
- **载荷的请求类型**：用记录或带 Jackson 注解的类声明。必填成员写 `@JsonProperty(required = true)`，可选成员不加注解（缺少时取 Java 的缺省值：基本类型为 0 / `false`，引用类型为 `null`）。`String`、`List` / 数组 / 其他集合、`Map`、嵌套的记录与 `JsonNode` / `Object` 接受 `null`（等于没有值）；整数、浮点与布尔不论是基本类型还是包装类型（`Long`、`Double`、`Boolean`）都不接受 `null`，它们的列表项与字典值同样不接受。认不出的类型（枚举、`java.time`、`Optional`、原语的 `Maybe` / `TypedId` 与其他自定义解码器、多态类型）在读到的位置单独交给 Jackson，失败时报 Jackson 给出的路径；`byte[]` 照 Jackson 读 Base64 字符串。
- **文本与字节**：`JsonText.check(String)`、`ManifestJson.read(String)`、`PrimitivesJson.read(mapper, String, …)` 先把字符串编成 UTF-8；字符串里有孤立的代理项（编不成 UTF-8）按语法错误处理，不像 `String.getBytes` 那样换成 `?`。
- **同名的 `Error`**：`com.xihanfun.framework.primitives.Error` 与 `java.lang.Error` 同名，在用到它的文件里显式导入；它不是 `Throwable`，误用在编译期就报错。

## 设计

**并发：虚拟线程上的同步签名，不用 `CompletableFuture`。** 中间件、处理器与调用器都是同步方法（`Result<Unit> invoke(Context, Step)`、`Result<Res> handle(Context, Req)`），按 Java 21 虚拟线程（JEP 444）设计：阻塞等待 I/O 时虚拟线程让出载体线程，不占平台线程，调用栈与异常栈可读，不需要把每一层都改成回调链。为此：构建出的 `Step`、`OperationInvoker`、`OperationRegistry` 不可变或线程安全，可以被任意多个线程同时使用；`Context` 不可变、按派生传递，每次操作调用的状态随派生的上下文传递（`Context.withValue`），嵌套与并发调用互不干扰；锁一律用 `ReentrantLock`，不在 `synchronized` 里阻塞（Java 21 上 `synchronized` 会钉住虚拟线程）。

**取消与截止时间自己建模。** `Context` 有两个令牌：截止令牌由定时器在截止时间触发，外部令牌由 `CancellableContext.cancel()` 触发；父级取消连带子级，子级取消不影响父级。归因只看令牌（RFC-0003 规则 1、RFC-0004、ADR-0095）：截止令牌已触发即 `KERNEL_TIMEOUT_001`，不论调用方是否也已取消；只有外部令牌触发时为 `KERNEL_CANCELLED_001`。`onCancelled` 登记回调（已取消时立即执行），`awaitCancellation` 在虚拟线程上等待；`withCancellation` / `withDeadline` / `withTimeout` 返回的 `CancellableContext` 用完要关闭（try-with-resources），免得长寿命的父级积攒已结束的子级。

**JSON：Jackson。** Java 标准库没有 JSON；XGI 载荷、清单读取与线上编码都需要它。选 `jackson-databind` 是因为它是 JVM 上事实标准、本身是 JPMS 模块、支持记录类型，grpc-java 等常用库的使用者通常已经有它。原语核心类型不依赖 Jackson（RFC-0001：原语层不含序列化代码）：共同的 JSON 文本检查 `JsonText` 是手写的逐字节检查器，编码在 `primitives.json` 包里；manifest 只用树模型按 Schema 逐项核对（`ManifestShape`）、不做反射绑定，写出是手写的排版；operation 用 `OperationJson.newMapper()`——注册原语编码、忽略未知字段、类型不符一律失败——并在反序列化之前按请求类型以文本顺序核对载荷（`RequestShape`），报错的先后不随 Jackson 的内部路径变。模块路径上的应用要把请求与响应类型所在的包 `opens` 给 `com.fasterxml.jackson.databind`。

**可空性。** 公开成员不接受也不返回 `null`：可能没有的值以 `Optional` 返回（`findBaggage`、`find`、`tenant()`），需要线上编码的数据字段用 `Maybe`（编码为值或 `null`，对象里缺这个字段也解码为无值），集合没有时是空集合；参数传 `null` 抛 `NullPointerException`。

**有界。** 对象袋最多 1024 个条目、Baggage 最多 180 个、按派生的值最多 256 个键、操作登记表 10000 个操作，超出是编程或配置错误。管道的中间件与顺序约束不设上限，与 .NET 的 `PipelineBuilder` 一致：它们由代码在启动时登记，不来自外部输入（上一版限 1024 个中间件与 4096 条约束，已撤回）。

## 开发

本机没有 Maven 时用 Docker 官方镜像构建与测试，命名卷缓存依赖：

```bash
git submodule update --init          # spec/
```

PowerShell：

```powershell
docker run --rm -v "${PWD}:/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

Git Bash（关掉路径转换）：

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

有 Maven 3.9+ 与 JDK 21+ 时直接 `mvn -B verify`。门禁：`maven.compiler.release` 21，编译带 `-Xlint:all -Werror` 与 `-Xdoclint:all,-missing`；测试是 JUnit 6（JUnit Platform），在类路径上运行，一致性向量按仓库根的相对路径读取（不复制向量文件）。容器以 root 写出的 `target/` 不提交。

## 状态

`0.1.0-SNAPSHOT`：原语、清单、调用上下文、管道与操作可用；进程外插件运行时（`plugin`，`xihan.plugin.v1`，grpc-java）与远程 Provider 的操作集（首个是 `caching.distributed@1`）规划中，示例随它们提供。正式发布时去掉 `-SNAPSHOT`，以 `v0.1.0` 标签发布到 Maven Central。

## 生态

| 项目 | 说明 |
| --- | --- |
| [framework-spec](https://github.com/XiHanFun/framework-spec) | XiHan Spec：RFC、术语表、协议映射与一致性向量 |
| [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework) | .NET 参考实现 |
| [framework-sdk-go](https://github.com/XiHanFun/framework-sdk-go) | Go SDK |
| [framework-sdk-typescript](https://github.com/XiHanFun/framework-sdk-typescript) | TypeScript SDK |
| [framework-sdk-rust](https://github.com/XiHanFun/framework-sdk-rust) | Rust SDK |
| [framework-sdk-python](https://github.com/XiHanFun/framework-sdk-python) | Python SDK |
| [framework-sdk-php](https://github.com/XiHanFun/framework-sdk-php) | PHP SDK |

## 参与

欢迎提交 [Issue](https://github.com/XiHanFun/framework-sdk-java/issues) 与 Pull Request。

1. 先读 framework-spec 的 README 与相关 RFC。
2. 公开契约的变更不在 SDK 仓库里做：走 framework-spec 的 RFC 流程，再同步到各 SDK。

## 许可证

[MIT](./LICENSE) © 2021-Present XiHanFun and contributors。
