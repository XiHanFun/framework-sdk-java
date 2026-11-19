# 与 .NET 对齐

## 事实的先后

1. 规范正文与向量（`spec/` 子模块钉的提交）。
2. 参考实现 XiHan.Framework 的源码与它回放同一份向量的测试；向量的期望值由 .NET 算出。
3. 其他 SDK 的实现只用来发现差异，不作为依据。

规范与 .NET 不一致时，以规范为准并报告差异；规范没写、.NET 有确定行为时，不在本仓库照抄了事，先把它写进规范（见下）。

## 规范没写到时

1. 写下可复现的输入，以及本 SDK、.NET、其他 SDK 各自的输出。
2. 提出规范文字（写在哪个 RFC 或 `plugin/*.md` 的哪一节）与向量用例，期望值取 .NET 的实测结果。
3. 在 framework-spec 提交并推送；XiHan.Framework 写 ADR（`plans/adr`）。
4. 回到本仓库升规范指针、补回放、改实现，提交说明写 ADR 编号与「规范指针升到 <短哈希>」。

## Java 里已知的坑

这些都是曾经让行为偏离规范的地方，改相关代码时先看这里：

- 空白：`Character.isWhitespace` 与 `String.isBlank` 不含 U+0085、U+00A0、U+2007、U+202F，却含 U+001C–U+001F；一律用 `internal.Whitespace`（RFC-0001「空白字符」）。
- 正则：`Matcher.find` 与 `$` 会放过结尾的换行；规范的正则一律整串匹配，用 `Matcher.matches()`。
- 文本与字节：`String.getBytes` 把孤立的代理项换成 `?`；先编成 UTF-8 的入口要把它当作语法错误。
- Jackson 的读取：会跳过 BOM、按字节猜 UTF-16、放过孤立的代理项，所以共同的 JSON 文本检查 `JsonText` 是手写的逐字节检查器，不经 Jackson；重复成员、64 层与孤立代理项由解码器自己核对，不看 `ObjectMapper` 的配置。
- Jackson 的报错顺序：随它的内部路径变，所以载荷先按请求类型以文本顺序核对（`RequestShape`），清单按 Schema 逐项核对（`ManifestShape`），再交给 Jackson。
- Jackson 的大小写：`ACCEPT_CASE_INSENSITIVE_PROPERTIES` 按 Unicode 换小写（会把 U+212A 当作 `k`）；配置节的成员名按 ASCII 不区分大小写，在交给 Jackson 之前改写（`ConfigurationNames`）。
- Jackson 的数字：编码时关掉 `WRITE_NAN_AS_STRINGS`，写出的文本再过一遍共同检查；64 位范围内的整数原样往返，其余按双精度浮点。
- 虚拟线程：`synchronized` 里阻塞会钉住载体线程；锁一律 `ReentrantLock`。
- 时钟：截止令牌由定时器按 `nanoTime` 触发，令牌已触发时墙上时钟可能还没到截止时间；判断截止时间是否已到时，截止令牌已触发或按时钟已过任一成立即算已到，不能只看 `Instant.now()`。

## 记录改动

改变了可观察行为或公开 API 时：

- Javadoc 写新行为与规范出处。
- 文档页 `../XiHan.Framework/docs/sdks/java.md` 的相应条目改成新行为，旧行为以「（上一版…）」注明；需要使用者改代码的，在「破坏性改动与迁移」一节写一条：改了什么、怎样迁移。
