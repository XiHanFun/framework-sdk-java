<div align="center">

<h1>XiHan Framework SDK for Java</h1>

<h3>The same primitives, manifests and pipeline as the .NET host, verified by shared conformance vectors</h3>

<p>
  <a href="https://github.com/XiHanFun/framework-sdk-java/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/XiHanFun/framework-sdk-java/ci.yml?branch=main&style=flat-square&label=CI" /></a>
  <img alt="JDK 21+" src="https://img.shields.io/badge/JDK-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" />
  <a href="./LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/XiHanFun/framework-sdk-java?style=flat-square&color=green" /></a>
  <a href="https://github.com/XiHanFun/framework-sdk-java/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/XiHanFun/framework-sdk-java?style=flat-square&logo=github&label=Stars&color=1f6feb" /></a>
</p>

<p>
  <a href="https://framework.docs.xihanfun.com"><b>Docs</b></a> ·
  <a href="#getting-started"><b>Getting Started</b></a> ·
  <a href="https://github.com/XiHanFun/framework-spec"><b>Specification</b></a> ·
  <a href="https://github.com/XiHanFun/XiHan.Framework"><b>Reference Implementation</b></a>
</p>

<p><b>English</b> | <a href="./README_cn.md">简体中文</a></p>

</div>

The Java implementation of [XiHan Spec](https://github.com/XiHanFun/framework-spec): the primitives layer, manifests, the invocation context, the pipeline and operation contracts. Use it to bring the same primitives as the .NET host (the reference implementation, [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework)) into JVM applications. The spec lives in `spec/` as a submodule, and conformance is verified by the conformance vectors of the spec repository.

## Modules

The whole SDK is a single Maven artifact, `com.xihanfun:xihan-framework`. Like the SDKs for other languages it is one package, with the JPMS module name `com.xihanfun.framework`, divided into Java packages:

| Java package | Contents | Spec |
| --- | --- | --- |
| `com.xihanfun.framework.primitives` | `Error` / `ErrorCode`, `Result<T>`, `Maybe<T>`, `TypedId<Tag, V>`, `LifecycleState`, `PipelineStage`, `Stability`, `BaggageKeys`, message keys and templates (`MessageTemplate`, `MessageCatalog`, `MessageScope`), `FrameworkException`, `FrameworkInfo` | RFC-0001, RFC-0010, `conventions/` |
| `com.xihanfun.framework.primitives.json` | Wire JSON encoding: the Jackson module `PrimitivesModule` | RFC-0001 (wire encoding) |
| `com.xihanfun.framework.manifest` | The `xihan.manifest.json` model, strict schema-based reading, normalized output and validation, capability declarations and requirement text | RFC-0002, `manifest/manifest.schema.json` |
| `com.xihanfun.framework.invocation` | `Context`: cancellation and deadlines (attributed by token to `KERNEL_TIMEOUT_001` / `KERNEL_CANCELLED_001`, with callback registration), W3C tracing, registered baggage, the item bag `Items`, values passed by derivation | RFC-0003 |
| `com.xihanfun.framework.pipeline` | The eleven-stage `PipelineBuilder`: `use` / `useAt` / `useNamedInstance`, `before` / `after` (type name or instance name), `describe` / `dump`, `build`; ordering rules and the error codes `KERNEL_PIPELINE_00x` | RFC-0004 |
| `com.xihanfun.framework.operation` | `OperationContract`, `OperationSchema`, `OperationHandler<Req, Res>`, `OperationRegistry` with a capacity of 10000, `OperationInvoker` that runs through the full pipeline (XGI: JSON request and response, plus a typed entry point) | RFC-0011 |

Dependency direction between packages: `operation → pipeline → invocation → primitives`, `manifest → primitives`. The only external dependency is `jackson-databind`, declared as `requires transitive` in the module descriptor.

## Getting Started

Requires JDK 21+. Maven coordinates `com.xihanfun:xihan-framework` (the Maven Central namespace `com.xihanfun` is verified; not yet published):

```xml
<dependency>
  <groupId>com.xihanfun</groupId>
  <artifactId>xihan-framework</artifactId>
  <version>${xihan-framework.version}</version>
</dependency>
```

Using the primitives in a Java application:

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

- Recoverable failures return `Result` / `Error` instead of throwing. Error codes follow `<DOMAIN>_<KIND>_<NNN>` with the domain in uppercase, and text uses message keys plus language files (RFC-0010). When there is no resolved text, `Error.message()` resolves it against the process-default catalog of `MessageScope`; if that cannot translate it, it uses the SDK's built-in English templates for kernel messages (`KernelMessages`, identical character for character to `kernel-messages.json`), and finally falls back to a `key (arguments)` description.
- Configuration errors (an invalid manifest, a pipeline cycle, a duplicate operation registration, a full registry) throw `FrameworkException` with an error code; programming errors by the caller, such as malformed arguments, throw `IllegalArgumentException`, and disallowed states throw `IllegalStateException`. Messages are English sentences, with identifiers in single quotes.
- The JSON encoding of `Result<T>`, `Error`, `Maybe<T>` and `TypedId` follows the Result section of RFC-0001: `new ObjectMapper().registerModule(new PrimitivesModule())`. Read text received from the wire with `PrimitivesJson.read(mapper, bytes, type)`, which runs the shared JSON text check first. Lifecycle transition tables, pipeline stage order, error codes, baggage keys and values, the capability requirement grammar, the JSON text check, wire encoding, message templates, kernel errors, manifest reading and writing, payload decoding and pipeline ordering are all verified by `spec/conformance/vectors`, with expected values matching character for character.

## Behavior Shared with the Host

Behavior unified by the spec's ADR-0233, ADR-0235 and ADR-0236, the same in every language SDK and in .NET:

- **JSON text**: the wire encoding of Result and Error, operation payloads and manifests all go through the same check first (`JsonText.check`, vector `json-text.json`): syntax follows RFC 8259 (no comments, trailing commas, `NaN`, leading zeros, plus signs or multiple root values; empty text and whitespace-only text are invalid too), the text is UTF-8, and a leading BOM is a syntax error; nesting is at most 64 levels; strings and member names must be valid Unicode (rejecting invalid UTF-8, overlong encodings, surrogates encoded as UTF-8, raw control characters, invalid escapes and lone surrogate escapes); and no level may contain a duplicate member (compared after unescaping). Syntax errors take precedence and report only `$` as the location; otherwise the path of the first duplicate is reported. Jackson skips a BOM, guesses UTF-16 from the bytes and lets lone surrogates through, so this pass does not go through Jackson.
- **Wire encoding**: a successful result with no value (`Result<Unit>`) is written only as `{"ok":true}`; the no-value case of `Result<Maybe<T>>` is `"value":null`. The `message` of an `Error` is required and non-empty; a missing `messageKey`, `details` or `inner` means there is none, and writing them as `null` is invalid; unknown members are ignored. Duplicate members are invalid at any level, including inside `value`, `details` and `inner` (the previous version did not check inside `value`); the decoder checks duplicates, the 64-level limit and lone surrogates itself, regardless of the `ObjectMapper` configuration. Numbers follow the value model: integer literals within the 64-bit range round-trip unchanged (9007199254740993 does not become 9007199254740992), and everything else is a double-precision float; floats with integral values in details are written as `2.0`, `-0.0`, `1.0E17`, and non-finite values as the strings `"NaN"`, `"Infinity"`, `"-Infinity"` (previously, non-finite values were not allowed in details).
- **Templates**: message keys are at most 128 characters long. Format specifiers are recognized only if they match `^[DdFfNnXx][0-9]{0,2}$`: `D` and `X` apply only to integers, `X` only to non-negative integers, and `F` and `N` to integers and floats; specifiers that do not apply or are not recognized (`E2`, `G`, `0.00`, `yyyy`, `D100`, ones containing spaces) are all written as unformatted (the previous version allowed precision up to 99). `F` and `N` expand to the exact decimal and round exact halves to even (0.125 with `F2` gives `0.12`, 2.675 with `F2` gives `2.67`), write all integer digits, and keep the minus sign when a negative number rounds to zero. Arrays and objects are written as compact JSON, with object members ordered by the UTF-16 code units of their names, and containers nested deeper than 64 levels written as `null` (the previous version used Java's `toString`). Floats with 1e-4 ≤ |x| < 1e17 are written without an exponent; outside that range they are written as `1E+17`, `9.9E-05`, `5E-324`, and negative zero as `-0`. `Describe` orders its arguments by the ordinal order of their names.
- **Locales**: `MessageTemplate.format(template, arguments, locale)` / `tryFormat(…, locale)` / `text(value, locale)` and `MessageCatalog.resolve(key, locale, arguments)` take the decimal separator and the group separator from the locale: de-DE, es-ES and pt-BR use `,` and `.`, fr-FR uses `,` and U+202F, ru-RU uses `,` and U+00A0, and every other locale, or no locale, uses `.` and `,`; locale names are case-insensitive. The decimal separator applies to unformatted floats (including the mantissa of an exponent form; 1.5e20 in de-DE is written `1,5E+20`) and to `F` and `N`; the group separator applies only to `N`. Unformatted integers are not grouped, and `D`, `X` and numbers inside arrays and objects do not vary by locale. `Error.message()` takes its symbols from the process-default locale of `MessageScope`, including when it falls back to the built-in en-US templates.
- **Kernel errors**: error codes, message keys and arguments follow `kernel-messages.json`, and the built-in English templates are identical to it character for character, including the 9 keys for manifest reading, so the `message` of a manifest read failure is the same English sentence as in .NET; `kernel.operation.type_mismatch` carries only `id` (the previous version carried the Java type names `expected` / `actual`). Duplicate registration and a full registry at configuration time still throw `FrameworkException`, and `toError()` yields the error with its key and arguments.
- **Built-in steps of the invoker**: they sit in the same positions as the built-in middleware of .NET, all placed at `Beginning` of their stage under the owner `xihan.kernel.core` (`OperationInvoker.KERNEL_OWNER`), and ordered among other middleware at the same position by the ordinal order of the owner, then by registration order within the same owner (built-in steps are registered after the user's middleware). The Observe stage translates exceptions (`KERNEL_UNEXPECTED_001` + `kernel.invocation.unhandled_exception`; the error of a `FrameworkException` goes into `inner`, exceptions caused by cancellation are attributed by token, and `java.lang.Error` is not translated), and catches only exceptions from what is ordered after it: exceptions thrown by middleware in the Ingress stage, and by middleware at `Beginning` of the Observe stage whose owner is ordered before it (such as `app`), are passed to the caller unchanged. The Resilience stage checks the deadline: if it has already passed on entry, the result is `KERNEL_TIMEOUT_001` + `kernel.invocation.deadline_before_start` (the previous version checked before the first middleware; withdrawn). The Validate stage decodes the payload of a raw invocation, and typed invocations skip it. Before entering the pipeline, only an unregistered operation (`KERNEL_NOTFOUND_001`) and a type mismatch in a typed invocation (`KERNEL_VALIDATION_002`) are checked, in that order. The invoker copies the user's `PipelineBuilder` (`copy()`) before adding the built-in steps, leaving it unchanged.
- **Response encoding**: the XGI entry point encodes the response into a payload only after the pipeline has finished, so middleware sees the result before encoding; `Operations.findResponse(context)` gives the response object from a successful handle stage (the previous version's `findResponsePayload` gave the payload; withdrawn). When the response cannot be written (a float member of a typed response is NaN or infinite, or a value is not supported by serialization), the invocation ends with `KERNEL_UNEXPECTED_001` + `kernel.invocation.unhandled_exception`, with the error of a `FrameworkException` in `inner` if one is on the exception chain; nothing is thrown to the caller, and there is no payload (the previous version used the English message of `Error.literal`). NaN is not written as a string: encoding turns off Jackson's `WRITE_NAN_AS_STRINGS`, and the written text goes through the shared JSON text check again. The typed entry point does not encode.
- **Cancellation only through tokens**: the invoker and the built-in steps do not interrupt threads or cancel futures; they only fire the context's token. If a handler still returns a result after the token has fired, the invocation ends with that result. A handler that needs to finish early checks `context.cancellation()`, `onCancelled` or `awaitCancellation` itself.
- **Payload decoding**: the shared check runs first (syntax errors report `$`, duplicate members report the path of the first duplicate, ahead of any type problem); an empty payload is equivalent to `{}`, and whitespace only is a syntax error (the previous version treated it as `{}`). The payload is then checked against the request type in text order (`RequestShape`, independent of Jackson's read order): member names are case-sensitive, unknown members are ignored, numbers are not read from strings, integers do not accept `1.0` or `1e2`, a value outside the type's range is a mismatch, floats accept integer literals, 1e400 overflows to infinity and 1e-400 becomes 0; a missing required member is reported at the end of its enclosing object, with that object's path. Payload text is compared as JSON values; whitespace, escapes and the spelling of numbers are not part of the contract. Members of arbitrary JSON type (`JsonNode`, `Object`) keep the value as read: integers beyond double precision stay exact, `2.0` and `2`, `-0.0` and `0` stay distinct, and numbers a double cannot represent, such as `1e400` and `1e-400`, keep the exact decimal of the original text; written back, they are equal as JSON values and do not turn into `null`, infinity or 0. `reason` carries only the path: `$` when the payload is `null`, is not an object, or fails at the root; `$['name']` when the member name contains `.`, a space, `'`, `/`, `"`, `[`, `]`, `(`, `)`, `\`, tab, line feed, carriage return, form feed, backspace, U+0085, U+2028 or U+2029; and `$.name` otherwise.
- **Manifest reading**: in the order JSON text → literal `null` → item-by-item schema check → cross-field constraints, each step reports only the first problem, and every error code is `KERNEL_CAPABILITY_006` (the previous version used `PRIMITIVES_VALIDATION_001` with an English message): `kernel.manifest.invalid_json` (`reason` is `$` or the path of the first duplicate), `kernel.manifest.empty` (the root is `null`; no arguments), `kernel.manifest.invalid` (`reason` is the path of the first problem: members are checked in text order, each in turn for being outside the schema → `null` → type → value, and after an object is done, missing members are checked in the order of `required`); cross-field constraints are checked in the order `primitives.manifest.isolation_required` → `isolation_not_allowed` → `duplicate_provides` → `duplicate_requires` → `duplicate_bindings`, with `id` as the only argument. `ManifestJson.read(byte[])` does not skip a BOM; `readFile` reads UTF-8 and skips a leading UTF-8 BOM, and a missing file is `kernel.manifest.file_missing` (argument `path`; previously it threw `IOException`). Interface versions have no leading zeros and do not exceed 2147483647, and only requirement versions may carry a single `^`; middleware names are non-empty, `contributes.health` is checked against `^[a-z][a-z0-9.-]*$`, and duplicates in `contributes.permissions` report the second occurrence; an `activation` written as an empty array is invalid. Middleware contributed by non-Wasm plugins is left for the host to reject at discovery, and reading and building manifests no longer check it (consistent with .NET). Cross-field constraints in `Manifest.Builder.build()` carry the same message keys and `id`, with the error code `PRIMITIVES_VALIDATION_001`.
- **Manifest writing**: the layout of `ManifestJson.write` is fixed character for character by the Writing section of RFC-0002: two-space indentation, LF, no trailing newline, `": "`; `$schema` is always written as `https://spec.xihanfun.com/manifest/v1.json`, lists are always written (an empty list as `[]`), an omitted `role` is written as `provider` and an omitted `activation` as `["onStartup"]`, requirements always write `optional`, bindings always write `required`, middleware always writes `before` / `after`, and `contributes` is always written; strings escape only `"`, `\`, control characters and lone surrogates (`<`, `&`, U+2028 and non-ASCII are written as is). The previous version omitted default values and escaped `<>&`. `Manifest` compares by effective values (ignoring `$schema`; unwritten roles, activation events, contribution points and the `required` of bindings take their defaults), and a written manifest, read back, equals the original.
- **Grammar**: every regular expression in the spec is a whole-string match, with no exception for a trailing newline (`xihan.tenant\n` and a `traceparent` with a newline do not pass); Java always uses `Matcher.matches()`. Versions in capability requirements do not accept leading zeros, `+`, whitespace or values above 2147483647.
- **Baggage values**: values of registered keys must be non-empty, at most 256 UTF-16 code units long, and free of U+0000–U+001F and U+007F–U+009F. For external input, `Context.builder().baggage(…)` drops members that do not conform and keeps an existing value of the same name; for in-process derivation, `withBaggage` throws `IllegalArgumentException` when a registered key is given a non-conforming value (the previous version removed the key). `xihan.idempotency-key` is exempt in both cases.
- **Tracing**: `traceparent` is accepted only if it matches `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$` and its IDs are not all zeros; an invalid one is treated as absent. Only the sampled bit is taken, and only `00` / `01` is written (the previous version kept the whole flags byte and had `traceFlags()`; withdrawn); without an upstream, `01` is written.
- **Pipeline**: duplicate registration, unknown stages, and constraint subjects that are unregistered or ambiguous by name are reported at registration; constraint targets that are unregistered or ambiguous, cross-stage constraints and cycles are reported at ordering time (`describe` / `dump` / `build`). Like .NET, ordering processes one stage at a time: within a stage, it resolves the constraints of each subject in baseline order (before first, then after, each in declaration order) and sorts only after resolving them all, so when several errors exist at once, the same one is reported. Name-based constraints use the same matching as .NET: an instance registered by name matches only its instance name, and a registration without a name matches the middleware name (in the previous version, the middleware name could also match an instance registered by name). Error messages are the original .NET English text, with an instance registered by name written as `TypeName[name]`; for a cycle, all middleware still blocked is listed in baseline order, for example `Middleware ordering constraints in stage 'validate' form a cycle: 'Guard[a]', 'Guard[b]'.`.

## Language Mapping

How Java constructs map to the spec:

- **Value model of template arguments** (RFC-0010, Templates): `null` is the null value; `Boolean` is a boolean; `String`, other `CharSequence` types and `Character` are strings; `Byte`, `Short`, `Integer`, `Long`, `AtomicInteger` and `AtomicLong` are 64-bit integers, and `BigInteger` is an integer within the 64-bit range and a double-precision float beyond it; `Double` is a float, while `Float` and `BigDecimal` are first converted to the nearest `double` (`0.1f` is written `0.10000000149011612`, `new BigDecimal("2.50")` is written `2.5`); a `Map` whose keys are all `String` is an object, and `Collection` and Java arrays (including primitive arrays) are arrays. Values outside the model (enums, `UUID`, `java.time` types, a `Map` whose keys are not all strings, and so on) are written as the text of `toString()`, and as a JSON string of that text when nested in an array or object; they do not cross languages, so a plugin that needs cross-language formatting converts them to strings first.
- **Numbers in the wire encoding**: `Error.details()` may hold `Integer` / `Long` / `BigInteger` / `Float` / `Double` / `BigDecimal`, all written according to the value model above; read back, integers within the 64-bit range are `Integer` or `Long`, everything else is `Double`, and non-finite values come back as strings. The content of `Result.value` is written by the value type's own Jackson configuration; when reading, integers beyond 64 bits first become floats according to the value model, and are then handed to the value type.
- **Payload request types**: declare them as records or as classes with Jackson annotations. Mark required members with `@JsonProperty(required = true)` and leave optional members unannotated (when missing, they take Java's default: 0 / `false` for primitive types, `null` for reference types). `String`, `List` / arrays / other collections, `Map`, nested records and `JsonNode` / `Object` accept `null` (meaning no value); integers, floats and booleans, whether primitive or boxed (`Long`, `Double`, `Boolean`), do not accept `null`, and neither do their list items and map values. Types that are not recognized (enums, `java.time`, `Optional`, the primitives' `Maybe` / `TypedId` and other types with custom decoders, polymorphic types) are handed to Jackson individually at the position where they are read, and a failure reports the path given by Jackson; `byte[]` reads a Base64 string as Jackson does.
- **Text and bytes**: `JsonText.check(String)`, `ManifestJson.read(String)` and `PrimitivesJson.read(mapper, String, …)` first encode the string as UTF-8; a lone surrogate in the string (which cannot be encoded as UTF-8) is treated as a syntax error, rather than replaced with `?` as `String.getBytes` does.
- **The `Error` name**: `com.xihanfun.framework.primitives.Error` has the same name as `java.lang.Error`, so import it explicitly in files that use it; it is not a `Throwable`, so misuse is reported at compile time.

## Design

**Concurrency: synchronous signatures on virtual threads, not `CompletableFuture`.** Middleware, handlers and the invoker are all synchronous methods (`Result<Unit> invoke(Context, Step)`, `Result<Res> handle(Context, Req)`), designed for Java 21 virtual threads (JEP 444): a virtual thread blocked waiting on I/O yields its carrier thread instead of occupying a platform thread, so call stacks and exception stack traces stay readable and no layer has to be rewritten as a callback chain. To that end, the built `Step`, `OperationInvoker` and `OperationRegistry` are immutable or thread-safe and can be used by any number of threads at once; `Context` is immutable and passed by derivation, and the state of each operation invocation travels with the derived context (`Context.withValue`), so nested and concurrent invocations do not interfere with each other; locks are always `ReentrantLock`, and nothing blocks inside `synchronized` (on Java 21, `synchronized` pins virtual threads).

**Cancellation and deadlines are modeled by the SDK itself.** `Context` has two tokens: the deadline token is fired by a timer at the deadline, and the external token by `CancellableContext.cancel()`; cancelling a parent cancels its children, and cancelling a child does not affect the parent. Attribution looks only at the tokens (RFC-0003 rule 1, RFC-0004, ADR-0095): if the deadline token has fired, the result is `KERNEL_TIMEOUT_001`, whether or not the caller has also cancelled; only when just the external token has fired is it `KERNEL_CANCELLED_001`. `onCancelled` registers a callback (run immediately if already cancelled), and `awaitCancellation` waits on a virtual thread; the `CancellableContext` returned by `withCancellation` / `withDeadline` / `withTimeout` must be closed after use (try-with-resources), so that long-lived parents do not accumulate finished children.

**JSON: Jackson.** The Java standard library has no JSON, and XGI payloads, manifest reading and the wire encoding all need it. `jackson-databind` was chosen because it is the de facto standard on the JVM, is itself a JPMS module and supports records, and users of common libraries such as grpc-java usually already have it. The core primitive types do not depend on Jackson (RFC-0001: the primitives layer contains no serialization code): the shared JSON text check `JsonText` is a hand-written byte-by-byte checker, and the encoding lives in the `primitives.json` package; manifest uses only the tree model to check item by item against the schema (`ManifestShape`), with no reflective binding, and writing uses a hand-written layout; operation uses `OperationJson.newMapper()` (which registers the primitives encoding, ignores unknown fields and fails on any type mismatch), and before deserializing checks the payload against the request type in text order (`RequestShape`), so the order in which errors are reported does not depend on Jackson's internal paths. Applications on the module path must `opens` the packages containing their request and response types to `com.fasterxml.jackson.databind`.

**Nullability.** Public members neither accept nor return `null`: values that may be absent are returned as `Optional` (`findBaggage`, `find`, `tenant()`), data fields that need wire encoding use `Maybe` (encoded as the value or `null`, and an object missing the field also decodes to no value), and absent collections are empty collections; passing `null` as an argument throws `NullPointerException`.

**Bounded.** The item bag holds at most 1024 entries, baggage at most 180, values passed by derivation at most 256 keys, and the operation registry 10000 operations; exceeding these is a programming or configuration error. Pipeline middleware and ordering constraints have no limit, consistent with the .NET `PipelineBuilder`: they are registered by code at startup and do not come from external input (the previous version limited them to 1024 middleware and 4096 constraints; withdrawn).

## Development

Without Maven installed locally, build and test with the official Docker image, caching dependencies in a named volume:

```bash
git submodule update --init          # spec/
```

PowerShell:

```powershell
docker run --rm -v "${PWD}:/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

Git Bash (with path conversion turned off):

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

With Maven 3.9+ and JDK 21+, run `mvn -B verify` directly. Gates: `maven.compiler.release` 21, compilation with `-Xlint:all -Werror` and `-Xdoclint:all,-missing`; tests use JUnit 6 (JUnit Platform) and run on the class path, and conformance vectors are read by relative path from the repository root (vector files are not copied). Do not commit the `target/` that the container writes as root.

## Status

`0.1.0-SNAPSHOT`: primitives, manifests, the invocation context, the pipeline and operations are usable; the out-of-process plugin runtime (`plugin`, `xihan.plugin.v1`, grpc-java) and the operation sets for remote providers (the first being `caching.distributed@1`) are planned, and samples will ship with them. For the official release, `-SNAPSHOT` will be dropped and the SDK published to Maven Central under the `v0.1.0` tag.

## Ecosystem

| Project | Description |
| --- | --- |
| [framework-spec](https://github.com/XiHanFun/framework-spec) | XiHan Spec: RFCs, vocabulary, protocol mappings and conformance vectors |
| [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework) | The .NET reference implementation |
| [framework-sdk-go](https://github.com/XiHanFun/framework-sdk-go) | Go SDK |
| [framework-sdk-typescript](https://github.com/XiHanFun/framework-sdk-typescript) | TypeScript SDK |
| [framework-sdk-rust](https://github.com/XiHanFun/framework-sdk-rust) | Rust SDK |
| [framework-sdk-python](https://github.com/XiHanFun/framework-sdk-python) | Python SDK |
| [framework-sdk-php](https://github.com/XiHanFun/framework-sdk-php) | PHP SDK |

## Contributing

[Issues](https://github.com/XiHanFun/framework-sdk-java/issues) and pull requests are welcome.

1. Start with the README and the relevant RFCs of framework-spec.
2. Changes to public contracts are not made in the SDK repositories: they go through the RFC process of framework-spec and are then synced to each SDK.

## License

[MIT](./LICENSE) © 2021-Present XiHanFun and contributors.
