# MixinTale

> A Harmony-like patching framework for Java, designed for EarlyPlugin-based mod loading.

MixinTale provides deterministic runtime patching for Java mods with a **single installable bootstrap jar** on the server side, while keeping developer tooling modular (`api` + `processor`) and runtime internals isolated (`core`).

---

## Table of contents

- [1) Why MixinTale](#1-why-mixintale)
- [2) Architecture overview](#2-architecture-overview)
- [3) Key concepts](#3-key-concepts)
- [4) Compatibility and prerequisites](#4-compatibility-and-prerequisites)
- [5) Server installation (EarlyPlugin)](#5-server-installation-earlyplugin)
- [6) Mod developer integration (Gradle)](#6-mod-developer-integration-gradle)
- [7) Quickstart: Hello Patch](#7-quickstart-hello-patch)
- [8) RedirectCall / WrapCall](#8-redirectcall--wrapcall)
- [9) Accessors](#9-accessors)
- [10) Runtime configuration](#10-runtime-configuration)
- [11) Troubleshooting](#11-troubleshooting)
- [12) Best practices](#12-best-practices)
- [13) API reference](#13-api-reference)
- [14) Reports and diagnostics](#14-reports-and-diagnostics)
- [15) Build & contribute](#15-build--contribute)
- [16) Security & safety](#16-security--safety)
- [17) License & support](#17-license--support)

---

## 1) Why MixinTale

MixinTale solves a common modding problem: applying runtime patches in a predictable, maintainable way across many mods.

It is built around:

- **Determinism**: stable patch ordering.
- **Separation of concerns**:
  - `api`: annotations/interfaces used by mod authors.
  - `processor`: compile-time index generation.
  - `core`: runtime weaving engine (no Hytale/Mixin imports).
  - `bootstrap`: EarlyPlugin adapter + service wiring.
- **Single install artifact** for operators: only `mixintale-bootstrap` is installed in runtime.

---

## 2) Architecture overview

```text
mod project(s)
 ├─ compileOnly -> mixintale-api
 ├─ annotationProcessor -> mixintale-processor
 └─ generated resource -> mixintale.index.json

server/runtime
 └─ mixintale-bootstrap (EarlyPlugin)
     ├─ scans mods directory
     ├─ loads mixintale.index.json from mod jars
     ├─ delegates to mixintale-core (ASM weaving)
     ├─ integrates Mixin service providers (META-INF/services)
     └─ writes JSON report on shutdown
```

### Module responsibilities

- **`api`**: public annotations (`@Patch`, `@Prefix`, `@Postfix`, `@Replace`, `@RedirectCall`, `@WrapCall`, `@Accessor`, `@Arg`, `@This`, `@Result`) + `Operation<R>`.
- **`processor`**: collects patch metadata and emits `mixintale.index.json`.
- **`core`**: index loading, class info resolution, weaving, runtime report generation.
- **`bootstrap`**: Early transformer entrypoint + Hytale integration + SPI declarations.

---

## 3) Key concepts

### Patch types

- **Prefix**: run logic before target method.
- **Postfix**: run logic after target method.
- **Replace**: replace target behavior.

### Callsite patching

- **RedirectCall**: reroute a specific callsite.
- **WrapCall**: wrap original invocation through `Operation<R>`.

### Accessors

Use accessor declarations to expose field access patterns where needed.

### Deterministic ordering

MixinTale uses stable sort keys and collision reporting so patch winners are reproducible.

---

## 4) Compatibility and prerequisites

- **Java**: 21+
- **Build**: Gradle (Kotlin DSL or Groovy DSL)
- **Runtime target**: EarlyPlugin-based Hytale server environment

> [!IMPORTANT]
> The runtime install artifact is **bootstrap only**. `core` is embedded/used by bootstrap and is not a separate server drop-in.

---

## 5) Server installation (EarlyPlugin)

1. Build or obtain `mixintale-bootstrap` artifact.
2. Install it as your EarlyPlugin jar (according to your server deployment layout).
3. Put mod jars (that include patch index metadata) in the mods directory.
4. Start with optional JVM properties (see [Runtime configuration](#10-runtime-configuration)).

### Mods directory resolution

Default mods path is taken from Hytale:

- `PluginManager.MODS_PATH`

Override for local/dev:

- `-Dmixintale.modsDir=/absolute/or/relative/path`

### Generated report

On shutdown, MixinTale writes a JSON report (under `logs/`) with patches/callsites/collisions/errors summary.

---

## 6) Mod developer integration (Gradle)

> Replace `VERSION` with your published MixinTale version.

### Kotlin DSL (`build.gradle.kts`)

```kotlin
dependencies {
    compileOnly("com.traktool:mixintale-api:VERSION")
    annotationProcessor("com.traktool:mixintale-processor:VERSION")
}
```

### Groovy DSL (`build.gradle`)

```groovy
dependencies {
    compileOnly "com.traktool:mixintale-api:VERSION"
    annotationProcessor "com.traktool:mixintale-processor:VERSION"
}
```

### Multi-module setup tip

In a root build, you can place these dependency conventions in `subprojects { ... }` for modules producing mods.

### IntelliJ note

Enable annotation processing for the project/module so `mixintale.index.json` is generated during build.

### Incremental annotation processing

`mixintale-processor` exposes Gradle AP metadata via:

- `META-INF/gradle/incremental.annotation.processors`

Use `--info` logs if AP behavior is unclear.

---

## 7) Quickstart: Hello Patch

Below is a minimal, compilable example using the current annotation contracts.

### Target class (example)

```java
package demo;

public final class TargetClass {
    public String greet(String name) {
        return "Hello " + name;
    }
}
```

### Patch class

```java
package demo;

import com.traktool.mixintale.api.*;

@Patch(targetClass = "demo.TargetClass", priority = 1000)
public final class TargetClassPatch {

    @Prefix(targetMethod = "greet", targetDesc = "(Ljava/lang/String;)Ljava/lang/String;")
    public static void before(@Arg(0) String name) {
        // pre-hook logic
    }

    @Postfix(targetMethod = "greet", targetDesc = "(Ljava/lang/String;)Ljava/lang/String;")
    public static void after(@Arg(0) String name) {
        // post-hook logic
    }
}
```

### Replace example

```java
package demo;

import com.traktool.mixintale.api.*;

@Patch(targetClass = "demo.TargetClass", priority = 1500)
public final class ReplacePatch {

    @Replace(targetMethod = "greet", targetDesc = "(Ljava/lang/String;)Ljava/lang/String;")
    public static String replace(@Arg(0) String name) {
        return "[Patched] Hi " + name;
    }
}
```

> [!WARNING]
> If multiple mods attempt competing `@Replace` semantics, collision outcomes depend on deterministic sort policy and should be validated through the runtime report.

---

## 8) RedirectCall / WrapCall

### RedirectCall

```java
package demo;

import com.traktool.mixintale.api.*;

@Patch(targetClass = "demo.TargetClass")
public final class RedirectExample {

    @RedirectCall(
        targetMethod = "greet",
        targetDesc = "(Ljava/lang/String;)Ljava/lang/String;",
        owner = "java/lang/StringBuilder",
        name = "append",
        desc = "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        ordinal = -1,
        require = 1
    )
    public static Object redirectAppend(Object builder, String text) {
        // custom redirection target
        return builder;
    }
}
```

- `ordinal = -1`: applies to all matching callsites.
- `require = N`: minimum expected replacements.

### WrapCall

```java
package demo;

import com.traktool.mixintale.api.*;

@Patch(targetClass = "demo.TargetClass")
public final class WrapExample {

    @WrapCall(
        targetMethod = "greet",
        targetDesc = "(Ljava/lang/String;)Ljava/lang/String;",
        owner = "demo/Helper",
        name = "compute",
        desc = "(Ljava/lang/String;)Ljava/lang/String;",
        ordinal = 0,
        require = 1
    )
    public static String wrap(String arg, Operation<String> operation) throws Throwable {
        String original = operation.call(arg);
        return "[Wrapped] " + original;
    }
}
```

---

## 9) Accessors

Example declaration:

```java
package demo;

import com.traktool.mixintale.api.*;

@Patch(targetClass = "demo.TargetClass")
public final class AccessorPatch {

    @Accessor("someField")
    public static Object accessField() {
        return null;
    }
}
```

Accessor capabilities and edge-case behavior should always be validated against runtime report output and target bytecode changes.

---

## 10) Runtime configuration

Currently supported system properties:

- `-Dmixintale.failHard=true|false`
  - `true`: throw early on fatal apply/require errors.
  - `false` (default): log warnings and continue when possible.

- `-Dmixintale.modsDir=/path`
  - override mods directory for development/testing.

> [!NOTE]
> If you need additional runtime flags (debug/log-level/frames policy), define them consistently in bootstrap/core and document them in this section.

---

## 11) Troubleshooting

### “No mixin host service is available” / Mixin service not found

Most common cause: shaded/fat jar overwrote SPI files.

**Fix**: in bootstrap Shadow configuration, ensure:

```kotlin
tasks.shadowJar {
    mergeServiceFiles()
}
```

This preserves `META-INF/services/*` providers for ServiceLoader.

### Patches do not apply

Checklist:

1. `mixintale.index.json` is present in mod jar.
2. Annotation processor is configured (`annotationProcessor`).
3. Mod jar is in mods directory scanned at runtime.
4. Target class/method descriptors match actual bytecode signatures.
5. Check report JSON for skipped patches and exceptions.

### ASM/frame errors

- Frame recomputation can be expensive and sensitive.
- `ClassWriter.COMPUTE_FRAMES` may require controlled superclass resolution (`getCommonSuperClass`) to avoid hostile classloading paths in modded environments.

### Annotation processing not running

- Ensure IDE AP is enabled.
- Run Gradle with `--info` and inspect AP execution logs.
- Confirm processor is on `annotationProcessor` configuration, not only `implementation`.

---

## 12) Best practices

- Use `priority` intentionally (reserve high priorities for compatibility-critical patches).
- Keep patches narrow and specific.
- Use `require` for callsite patches to detect upstream changes early.
- Track collisions in reports when interoperating with other mods.
- Version your mods carefully and test against target server updates.

---

## 13) API reference

### Annotation overview

| Annotation | Purpose | Target | Key fields |
|---|---|---|---|
| `@Patch` | Declares patch container | class | `targetClass`, `priority` |
| `@Prefix` | Before method hook | method | `targetMethod`, `targetDesc` |
| `@Postfix` | After method hook | method | `targetMethod`, `targetDesc` |
| `@Replace` | Replace method behavior | method | `targetMethod`, `targetDesc` |
| `@RedirectCall` | Redirect matching callsites | method | owner/name/desc, `ordinal`, `require` |
| `@WrapCall` | Wrap matching callsites | method | owner/name/desc, `ordinal`, `require` |
| `@Accessor` | Access target member | method | `value` |
| `@Arg` | Bind handler arg index | parameter | `value` |
| `@This` | Bind instance receiver | parameter | — |
| `@Result` | Bind result slot | parameter | — |

### `Operation<R>`

```java
@FunctionalInterface
public interface Operation<R> {
    R call(Object... args) throws Throwable;
}
```

Use in wrap handlers to invoke original behavior in-chain.

---

## 14) Reports and diagnostics

MixinTale writes a JSON apply report (default: `logs/mixintale-report-<timestamp>.json`) containing:

- metadata (timestamp, jars scanned)
- index summary
- patches applied / failed
- callsite replacement counts
- collisions (winner/losers)
- runtime errors

When reporting bugs, attach:

1. the report JSON,
2. server log excerpt,
3. minimal reproduction mod set.

---

## 15) Build & contribute

```bash
./gradlew build
```

Modules:

- `api/`
- `processor/`
- `core/`
- `bootstrap/`

Artifacts are generated by the Gradle module tasks (`jar`, `shadowJar`, etc.).

---

## 16) Security & safety

MixinTale is a bytecode patching framework. Use responsibly.

- Do not use runtime patching for malware-like behavior or unauthorized tampering.
- Prefer explicit, documented patches over hidden side effects.
- Validate third-party mods before deployment.
- Keep production server backups before introducing new patch sets.

---

## 17) License & support

See repository `LICENSE` for license terms.

For support, use your project’s configured issue tracker / discussion channels and include diagnostics from the report section.
