# MixinTale — Zero-Runtime-Dependency Bytecode Patching for Hytale Mods

> **TL;DR**: MixinTale lets mods patch Hytale server bytecode using a clean, annotation-driven API **without requiring patch classes to be loadable at runtime** (critical with isolated mod classloaders and strong encapsulation on modern Java).
>
> It ships as:
> - a **tiny API** (`mixintale-api`) for patch annotations and handler parameter markers,
> - a **compile-time annotation processor** (`mixintale-processor`) that generates an index inside your mod jar,
> - a **runtime weaver** (`mixintale-core`) that applies patches during class loading,
> - an **early bootstrap** jar that registers a minimal Sponge Mixin service (so the bootstrapping environment stays stable).

---

## Table of Contents

1. [Why MixinTale exists](#why-mixintale-exists)  
2. [Design constraints (the non-negotiables)](#design-constraints-the-non-negotiables)  
3. [Core ideas](#core-ideas)  
4. [Project layout](#project-layout)  
5. [Quick start (for mod developers)](#quick-start-for-mod-developers)  
6. [Patch types & semantics](#patch-types--semantics)  
   - [@Patch](#patch)  
   - [@Prefix](#prefix)  
   - [@Postfix](#postfix)  
   - [@Replace](#replace)  
   - [@RedirectCall](#redirectcall)  
   - [@WrapCall](#wrapcall)  
   - [@Accessor](#accessor)  
7. [Handler parameters: @This, @Arg, @Result, Operation](#handler-parameters-this-arg-result-operation)  
8. [Finding method descriptors (targetDesc, call desc)](#finding-method-descriptors-targetdesc-call-desc)  
9. [Callsite selection: owner/name/desc + ordinal + require](#callsite-selection-ownernamedesc--ordinal--require)  
10. [Ordering rules and interaction](#ordering-rules-and-interaction)  
11. [Failure modes, logging, and “require” guarantees](#failure-modes-logging-and-require-guarantees)  
12. [Compatibility, security, and performance notes](#compatibility-security-and-performance-notes)  
13. [FAQ / troubleshooting](#faq--troubleshooting)  
14. [Authoring guidelines (best practices)](#authoring-guidelines-best-practices)  
15. [Appendix: internal architecture (for contributors)](#appendix-internal-architecture-for-contributors)  

---

## Why MixinTale exists

Hytale server modding often needs **surgical changes** in the base game code:
- fix engine bugs,
- change a hardcoded rule,
- intercept a single call deep in logic,
- add custom behavior around an existing method,
- adjust return values and parameters precisely.

Traditional mixin approaches (e.g., Sponge Mixin style) typically assume:
- patch classes are available at runtime,
- mixin config files are loaded,
- classloader topology is “flat enough” to resolve patch classes,
- instrumentation occurs in a predictable environment.

Modern Hytale server setups and Java versions add real constraints:
- **mods may be isolated** in separate classloaders,
- Java 21+ strong encapsulation can break reflective “reaching into” patch classes,
- a TransformingClassLoader pipeline may reject bytecode referencing non-visible classes.

### MixinTale’s approach

MixinTale is designed around one principle:

> **Never emit bytecode that needs to reference your patch class at runtime.**

Instead, MixinTale **copies your handler methods into the target class** and then injects calls to *those copied methods*.  
That means:
- no `NoClassDefFoundError` when the target class executes code,
- no cross-classloader runtime references,
- patches become “self-contained” in the target class once applied.

---

## Design constraints (the non-negotiables)

MixinTale is intentionally strict and opinionated. The following constraints are **core to its reliability**:

1. **No runtime references to patch classes**  
   Handlers from your patch class are **relocated (copied) into the target class**.  
   This prevents runtime classloader visibility issues and is critical on Java 21+.

2. **Deterministic transforms**  
   Patch application order and matching are deterministic, with stable sorting rules.

3. **Fail-soft by default, fail-hard when you choose**  
   The weaver can run in a mode where patch failures are recorded (reporting) but do not crash the server,
   or in a strict mode where any mismatch is fatal.

4. **Index-driven patch discovery**  
   MixinTale does not rely on runtime reflection scanning for annotations inside mod jars.  
   Instead it uses a **compile-time annotation processor** to generate `mixintale.index.json` inside each mod jar.

---

## Core ideas

MixinTale is based on a small set of well-defined concepts:

### Patch class
A *patch class* is a class annotated with `@Patch(targetClass = "...")`.

It contains *handlers* (static methods are strongly recommended) annotated with:
- `@Prefix`
- `@Postfix`
- `@Replace`
- `@RedirectCall`
- `@WrapCall`
- (optionally) `@Accessor`

### Action
Each annotated handler corresponds to an **action descriptor** stored in the generated index:
- what kind of action it is (PREFIX/POSTFIX/REPLACE/REDIRECT/WRAP),
- which method on the target class it affects (`targetMethod`, `targetDesc`),
- for callsite actions: which invocation is targeted (`owner`, `name`, `desc`, plus `ordinal` and `require`).

### Weaver
At runtime, MixinTale:
1. reads the `mixintale.index.json` files from mod jars,
2. groups actions by target class,
3. during class loading, applies relevant actions to that target class bytecode using ASM.

---

## Project layout

Typical published setup (recommended):

- **MixinTale-Bootstrap** (early plugin jar)
  - registers the minimal Sponge Mixin service bootstrap
  - ensures the transformation environment starts cleanly and early

- **mixintale-api** (dependency for mod dev)
  - contains annotations and helper marker types:
    - `@Patch`, `@Prefix`, `@Postfix`, `@Replace`, `@RedirectCall`, `@WrapCall`, `@Accessor`
    - parameter markers `@This`, `@Arg`, `@Result`
    - `Operation<R>` functional interface for WRAP calls

- **mixintale-processor** (annotation processor)
  - scans patch classes at compile time
  - generates `mixintale.index.json` into your jar under `CLASS_OUTPUT`

- **mixintale-core** (runtime implementation)
  - class info resolver (hierarchy lookups for safe frame computation)
  - resource locator for jar scanning
  - transformer/weaver applying actions

---

## Quick start (for mod developers)

### 1) Add dependencies

You need:
- `mixintale-api` as `compileOnly` (or `implementation` if you want, but compileOnly is typical)
- `mixintale-processor` as `annotationProcessor`

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    compileOnly(files("MixinTale-API-x.x.x.jar"))
    annotationProcessor(files("MixinTale-Processor-x.x.x.jar"))
}
```

**Gradle (Groovy)**

```groovy
dependencies {
    compileOnly files("MixinTale-API-x.x.x.jar")
    annotationProcessor files("MixinTale-Processor-x.x.x.jar")
}
```

### 2) Write a patch

```java
import com.traktool.mixintale.api.*;

@Patch(targetClass = "com/hypixel/hytale/server/core/some/TargetClass")
public final class ExamplePatch {

    @Prefix(targetMethod = "doSomething", targetDesc = "(I)Z")
    public static void beforeDoSomething(@This Object self, @Arg(0) int value) {
        System.out.println("Called doSomething with: " + value);
    }
}
```

### 3) Build your mod jar

When compilation runs, the processor generates:

- `mixintale.index.json` inside your output classes, packed into the jar.

Verify it exists:

```bash
jar tf your-mod.jar | grep mixintale.index.json
```

### 4) Installation (singleplayer & server)

MixinTale is a **required dependency** for any mod that ships MixinTale patches.

#### Step 1 — Install MixinTale (required)

1. Download the **MixinTale** JAR (the early/bootstrapping JAR provided by the project).
2. Place it in the **EarlyPlugins** folder:

   - **Singleplayer**: `[GameFolder]/UserData/EarlyPlugins`
   - **Dedicated server**: `[ServerFolder]/EarlyPlugins`

3. **Server only**: start the server with the flag that allows early plugins:

```bash
java -jar HytaleServer.jar --accept-early-plugins ...
```

> If you forget `--accept-early-plugins`, the server may ignore the EarlyPlugins folder, and MixinTale will not initialize.

#### Step 2 — Install your mod (the mod that uses MixinTale)

1. Download the mod JAR.
2. Place it in the standard **Mods** folder:

   - **Singleplayer**: `[GameFolder]/UserData/Mods`
   - **Dedicated server**: `[ServerFolder]/Mods`

3. Start the game/server.

In most cases, no extra configuration is required unless the mod explicitly documents it.

---

## Patch types & semantics

All patch handlers live in a class annotated with `@Patch`.

> **Important**: internal names use slashes, not dots.  
> `com.example.Foo` → `com/example/Foo`

### @Patch

```java
@Retention(CLASS)
@Target(TYPE)
public @interface Patch {
    String targetClass();
    int priority() default 1000;
}
```

**Example**

```java
import com.traktool.mixintale.api.*;

@Patch(
    targetClass = "com/hypixel/hytale/server/core/modules/items/ItemRepairSystem",
    priority = 1000
)
public final class NoRepairPenaltyPatch {
    private NoRepairPenaltyPatch() {}
}
```

> The patch class can contain one or more handlers (`@Prefix`, `@Postfix`, `@Replace`, `@RedirectCall`, `@WrapCall`, etc.).


- **targetClass**: internal name of the class to be transformed.
- **priority**: controls ordering if multiple patches target the same class.
  - Lower priority generally means applied earlier (depending on the runtime sorting policy).
  - In practice: keep defaults unless you have a strong reason.

---

### @Prefix

```java
@Retention(CLASS)
@Target(METHOD)
public @interface Prefix {
    String targetMethod();
    String targetDesc();
}
```

Runs at the start of the target method.

Typical uses:
- logging,
- guarding,
- precondition modification,
- argument normalization (if supported by the action’s parameter semantics).

**Example**

```java
@Prefix(targetMethod = "tick", targetDesc = "()V")
public static void beforeTick(@This Object self) {
    // ...
}
```

---

### @Postfix

```java
@Retention(CLASS)
@Target(METHOD)
public @interface Postfix {
    String targetMethod();
    String targetDesc();
}
```

**Example (observing + overriding return value)**

```java
@Postfix(targetMethod = "computeCost", targetDesc = "(I)I")
public static int afterComputeCost(@This Object self, @Arg(0) int baseCost, @Result int current) {
    // current is what vanilla computed; return a new value to override it
    return Math.max(0, current - 5);
}
```

> If your MixinTale build treats `@Postfix` handlers as "return value capable", returning a value will replace the original return.
> For `void` methods, use a `void` handler and omit `@Result`.


Runs near the end of the target method.

Typical uses:
- cleanup,
- metrics,
- tracking final state,
- adjusting return values (with `@Result`, see below).

---

### @Replace

```java
@Retention(CLASS)
@Target(METHOD)
public @interface Replace {
    String targetMethod();
    String targetDesc();
}
```

Replaces the entire method body.

Typical uses:
- turning off a hardcoded behavior,
- implementing an alternate algorithm,
- hotfixing broken logic.

**Example**

```java
@Replace(targetMethod = "computeDamage", targetDesc = "(I)I")
public static int computeDamageReplacement(@This Object self, @Arg(0) int base) {
    return Math.max(1, base / 2);
}
```

---

### @RedirectCall

```java
@Retention(CLASS)
@Target(METHOD)
public @interface RedirectCall {
    String targetMethod();
    String targetDesc();
    String owner();
    String name();
    String desc();
    int ordinal() default -1;
    int require() default 1;
}
```

**Example (redirect a specific callsite)**

```java
@RedirectCall(
    targetMethod = "repairItem",
    targetDesc   = "(Lcom/hypixel/hytale/server/core/modules/items/ItemStack;I)I",
    owner        = "com/hypixel/hytale/server/core/modules/items/DurabilityMath",
    name         = "applyRepairPenalty",
    desc         = "(II)I",
    ordinal      = 0,
    require      = 1
)
public static int redirectApplyRepairPenalty(@Arg(0) int durability, @Arg(1) int repairAmount) {
    // Completely bypass the vanilla penalty logic:
    return durability + repairAmount;
}
```

What this does:
- Finds the **first** call (`ordinal = 0`) to `DurabilityMath.applyRepairPenalty(int,int)` inside `repairItem(...)`
- Rewrites that invocation to call your handler instead.


Targets a specific **call instruction** inside a target method and rewrites it to call your handler instead.

Use when:
- you want to replace a particular `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, etc,
- you want to route a call to custom logic,
- you can reconstruct the return value yourself.

---

### @WrapCall

```java
@Retention(CLASS)
@Target(METHOD)
public @interface WrapCall {
    String targetMethod();
    String targetDesc();
    String owner();
    String name();
    String desc();
    int ordinal() default -1;
    int require() default 1;
}
```

**Example (wrap a callsite and still call vanilla)**

```java
@WrapCall(
    targetMethod = "repairItem",
    targetDesc   = "(Lcom/hypixel/hytale/server/core/modules/items/ItemStack;I)I",
    owner        = "com/hypixel/hytale/server/core/modules/items/DurabilityMath",
    name         = "applyRepairPenalty",
    desc         = "(II)I",
    ordinal      = 0,
    require      = 1
)
public static int wrapApplyRepairPenalty(
        Operation<Integer> original,
        @Arg(0) int durability,
        @Arg(1) int repairAmount
) throws Throwable {

    // Example: reduce the penalty effect by altering arguments
    int adjustedRepair = repairAmount * 2;
    int vanilla = original.call(durability, adjustedRepair);

    // Example: post-process the result too
    return Math.max(vanilla, durability);
}
```

Use WRAP when:
- you want to keep vanilla behavior available,
- you want pre/post logic around the original call.


Wraps a callsite but still allows you to call the original invocation via an `Operation<R>`.

Use when:
- you want to add behavior *around* an existing call,
- you want to modify parameters before calling original,
- you want conditional logic that sometimes falls back to vanilla behavior.

---

### @Accessor

```java
@Retention(CLASS)
@Target(METHOD)
public @interface Accessor {
    String value();
}
```

**Example (field accessor pattern)**

```java
@Patch(targetClass = "com/hypixel/hytale/server/core/modules/items/ItemStack")
public final class ItemStackAccessors {

    // Access a private field (example name): "durability"
    @Accessor("durability")
    public static int getDurability(@This Object self) {
        throw new AssertionError("generated/rewritten by MixinTale");
    }

    @Accessor("durability")
    public static void setDurability(@This Object self, @Arg(0) int value) {
        throw new AssertionError("generated/rewritten by MixinTale");
    }
}
```

Notes:
- The body is never meant to run; it is a *template* for the weaver.
- Exact capabilities depend on the MixinTale build you ship (field vs method access, getter/setter conventions, etc.).


`@Accessor` is designed for generating access to a target field or method (implementation depends on the runtime weaver support).

Common patterns:
- access private fields safely (without reflection),
- bridge to internal data without patching callsites.

> Note: If you are using a MixinTale build where `ACCESSOR` is not yet implemented in the weaver, treat it as reserved for future/extended builds.

---

## Handler parameters: @This, @Arg, @Result, Operation

MixinTale supports a minimal set of parameter “roles” for handlers.  
They are encoded via parameter annotations:

### @This

```java
@Retention(CLASS)
@Target(PARAMETER)
public @interface This {}
```

Injects the receiver (`this`) for instance contexts.

- On instance methods: `@This` refers to the instance of the target class.
- On static methods: `@This` is not meaningful (unless the action defines it in a special context).

**Example (instance context)**

```java
@Prefix(targetMethod = "tick", targetDesc = "()V")
public static void beforeTick(@This Object self) {
    // 'self' is the instance of the target class currently executing tick()
}
```

**Example (callsite receiver binding)**

```java
@RedirectCall(
    targetMethod = "update",
    targetDesc   = "()V",
    owner        = "com/hypixel/hytale/server/core/Foo",
    name         = "bar",
    desc         = "(I)I",
    ordinal      = 0,
    require      = 1
)
public static int redirectBar(@This Object receiver, @Arg(0) int x) {
    // 'receiver' is the Foo instance the original INVOKEVIRTUAL would have used
    return x; // example
}
```


### @Arg(index)

```java
@Retention(CLASS)
@Target(PARAMETER)
public @interface Arg {
    int value();
}
```

Binds a handler parameter to the Nth argument of the target method or invocation.

Examples:
- In `@Prefix/@Postfix/@Replace`: the args are the target method’s args.
- In `@RedirectCall/@WrapCall`: the args correspond to the callsite invocation arguments (plus receiver if non-static).

**Example (method arguments)**

```java
@Prefix(targetMethod = "setHealth", targetDesc = "(I)V")
public static void clampHealth(@Arg(0) int newHealth) {
    int clamped = Math.max(0, Math.min(100, newHealth));
    // If your MixinTale build supports argument rewriting, you would return/assign the new value here.
    // Otherwise, use @WrapCall/@RedirectCall around the logic that consumes the value.
}
```

**Example (callsite arguments)**

```java
@WrapCall(
    targetMethod = "attack",
    targetDesc   = "(Lcom/hypixel/.../Entity;I)V",
    owner        = "com/hypixel/.../DamageSystem",
    name         = "applyDamage",
    desc         = "(Lcom/hypixel/.../Entity;I)I",
    ordinal      = 0,
    require      = 1
)
public static int wrapApplyDamage(Operation<Integer> original, @Arg(0) Object target, @Arg(1) int amount) throws Throwable {
    return original.call(target, amount + 1);
}
```


### @Result

```java
@Retention(CLASS)
@Target(PARAMETER)
public @interface Result {}
```

Used when you need access to the current return value:
- in `@Postfix` to observe/modify what will be returned,
- in WRAP calls to modify the result of the original call.

**Example (postfix return override)**

```java
@Postfix(targetMethod = "getDurability", targetDesc = "()I")
public static int afterGetDurability(@This Object self, @Result int current) {
    // 'current' is what vanilla would return.
    // Returning a new value replaces it (when return-overrides are enabled for postfix).
    return Math.max(1, current);
}
```

**Example (wrap call result post-processing)**

```java
@WrapCall(
    targetMethod = "repairItem",
    targetDesc   = "(Lcom/hypixel/.../ItemStack;I)I",
    owner        = "com/hypixel/.../DurabilityMath",
    name         = "applyRepairPenalty",
    desc         = "(II)I",
    ordinal      = 0,
    require      = 1
)
public static int wrapPenalty(Operation<Integer> original, @Arg(0) int d, @Arg(1) int a) throws Throwable {
    int result = original.call(d, a);
    return Math.max(result, d); // never reduce durability below original
}
```


### Operation<R>

```java
@FunctionalInterface
public interface Operation<R> {
    R call(Object... args) throws Throwable;
}
```

Only used for `@WrapCall`.

When MixinTale wraps a callsite, it provides an `Operation<R>` you can invoke to execute the original call.

**Example: WRAP callsite**

```java
@WrapCall(
    targetMethod = "handleHit",
    targetDesc   = "(Lcom/hypixel/.../Entity;I)V",
    owner        = "com/hypixel/.../DamageSystem",
    name         = "applyDamage",
    desc         = "(Lcom/hypixel/.../Entity;I)I",
    ordinal      = -1, // all matches
    require      = 1
)
public static int wrapApplyDamage(
        Operation<Integer> original,
        @Arg(0) Object entity,
        @Arg(1) int amount
) throws Throwable {

    int boosted = amount + 2;
    int result = original.call(entity, boosted); // call original
    return result;
}
```

> **Note**: `Operation.call(Object... args)` is intentionally generic because callsites vary widely.  
> Your handler is responsible for passing appropriate arguments.

---

## Finding method descriptors (targetDesc, call desc)

MixinTale matches methods by **JVM descriptors**. These must be exact.

Examples:
- `void tick()` → `()V`
- `int add(int a, int b)` → `(II)I`
- `String name(UUID id)` → `(Ljava/util/UUID;)Ljava/lang/String;`
- `void set(List<String> v)` → `(Ljava/util/List;)V`

### Recommended tools

- `javap -s -p -classpath <...> com.example.TargetClass`
- ASMifier (ASM “textifier”) or IDE bytecode viewer
- Decompiler + signature inspector (still double-check with `javap`!)

### `owner` internal names
For callsites (WRAP/REDIRECT), `owner` must be the internal name:
- `com/example/Foo` not `com.example.Foo`

### Arrays & primitives
- `int[]` → `[I`
- `Object[]` → `[Ljava/lang/Object;`

---

## Callsite selection: owner/name/desc + ordinal + require

Callsite actions (`@RedirectCall`, `@WrapCall`) match a specific invocation inside a method.

Matching keys:
- `owner` (internal name)
- `name` (invoked method name, or `<init>` for constructors)
- `desc` (invoked method descriptor)

### ordinal
- `ordinal = -1` means “match all”.
- `ordinal = 0` means “the first match in instruction order”.
- `ordinal = 1` means “the second match”, etc.

This is extremely useful when a method calls the same function multiple times.

### require
- minimum number of replacements expected.

If fewer matches are replaced than `require`, that is treated as a failure condition:
- in **fail-soft mode**: reported + logged, server continues (but patch may be partially applied / ineffective).
- in **fail-hard mode**: the transformer throws and class load fails (recommended for dev and CI).

---

## Ordering rules and interaction

For a single target method, actions are applied in a deterministic order:

1. `PREFIX`
2. `REDIRECT` / `WRAP` (callsite modifications)
3. `POSTFIX`
4. `REPLACE` (last)

Why?
- Prefix should run before any body modifications,
- Redirect/Wrap must see the method body before postfix rewriting,
- Replace invalidates prior injection points, so it is applied last.

If you define both REPLACE and PREFIX/POSTFIX on the same target method:
- REPLACE will typically override the final body, making other injections irrelevant.
- Don’t do that unless you absolutely know what you are doing.

---

## Failure modes, logging, and “require” guarantees

MixinTale is built to be **debuggable**, not mysterious.

Common failure sources:
- wrong target descriptor (most common),
- wrong callsite owner/name/desc,
- ordinal mismatch (you expected the 2nd call, but it moved),
- changes in Hytale upstream between versions,
- multiple mods patching the same method in incompatible ways.

### The apply report

A typical runtime collects an apply report with:
- which patches were considered,
- which actions were applied,
- how many callsites were replaced,
- errors and reasons.

This report is invaluable for:
- CI sanity checks against a specific server build,
- version migration,
- modpack compatibility debugging.

---

## Compatibility, security, and performance notes

### Java compatibility
MixinTale targets modern Java (at least 21) and is built with the reality of:
- module boundaries,
- strong encapsulation,
- tighter classloader visibility.

### Security posture
MixinTale does not:
- open reflective access into unrelated modules,
- require `--add-opens` for typical operation (depending on server environment),
- ship “god mode” reflection hooks.

It performs bytecode transformation, which is powerful, but it keeps runtime dependencies minimal.

### Performance
- Index-driven discovery avoids expensive classpath scanning.
- Patch application occurs during class loading and is typically a one-time cost per class.
- Deterministic sorting reduces nondeterministic cache misses and “heisenbugs”.

---

## FAQ / troubleshooting

### “My patch compiles but doesn’t apply”
Checklist:
1. Does your jar contain `mixintale.index.json`?
2. Is your `@Patch.targetClass` using **slashes**?
3. Are `targetMethod` and `targetDesc` exact?
4. For callsites: is `owner` slash-form and is `desc` exact?
5. Did the target method change in the server version you run?

### “I get `require failed: replaced < require`”
That means MixinTale matched fewer callsites than you required.
Usually:
- callsite was optimized away,
- the method’s internal structure changed,
- your `ordinal` is wrong,
- owner/name/desc doesn’t match the actual invoked method.

### “It crashes with `NoClassDefFoundError` pointing to my patch class”
This should not happen if the weaver correctly relocates handler methods.
If it does:
- you may be using an older/modified build,
- or the handler was not copied, and the injected bytecode references your patch class directly.

In MixinTale’s intended design, injected bytecode must call a handler **owned by the target class**.

---

## Authoring guidelines (best practices)

1. **Prefer WRAP over REDIRECT** when you want to preserve vanilla behavior.
2. Always set **require** for callsite patches.  
   Your patch should fail loudly when upstream changes.
3. Avoid patching extremely hot methods unless necessary.
4. Keep handlers small and deterministic.
5. Do not rely on local variables unless your MixinTale build explicitly supports them.
6. Use stable “anchor points” when possible (calls to known methods).
7. When multiple mods patch the same class, use priorities carefully and document the intent.

---

## Appendix: internal architecture (for contributors)

This section is for people working on MixinTale itself.

### Compile-time indexing (processor)

`MixinTaleProcessor` (annotation processor) scans for:

- `@Patch` classes
- annotated methods inside those patch classes

It writes a JSON index to:

- `CLASS_OUTPUT/mixintale.index.json`

Structure (conceptual):

```json
{
  "version": "1",
  "generatedAt": "2026-02-21T00:00:00Z",
  "patches": [
    {
      "patchClass": "com.example.ExamplePatch",
      "targetClass": "com/hypixel/.../TargetClass",
      "priority": 1000,
      "actions": [
        {
          "kind": "PREFIX",
          "methodName": "beforeDoSomething",
          "methodDesc": "(Ljava/lang/Object;I)V",
          "targetMethod": "doSomething",
          "targetDesc": "(I)Z"
        }
      ]
    }
  ]
}
```

Key point:
- the processor emits method descriptors for handler methods too (`methodDesc`) so the weaver can relocate and invoke them precisely.

### Runtime weaving pipeline (overview)

At runtime, the weaver:
1. parses target class bytecode to `ClassNode`,
2. groups actions by `targetMethod + targetDesc`,
3. for each action:
   - loads patch class bytecode from the mod jar (ResourceLocator),
   - **copies** the handler method into the target class (renaming if needed to avoid collisions),
   - injects or rewrites bytecode in the target method to call the copied handler.

**Why copy?**  
Because the patch class is usually loaded from a mod classloader that the target class will not see.

### Stable ordering

Actions in a method are sorted by kind in this order:
- PREFIX (0)
- REDIRECT/WRAP (1)
- POSTFIX (2)
- REPLACE (3)

This is done to ensure deterministic results.

### Frame computation and class hierarchy safety

MixinTale typically writes classes with:
- `COMPUTE_FRAMES` and `COMPUTE_MAXS`

Computing frames requires access to the class hierarchy.  
A `ClassInfoResolver` / `SafeClassWriter` is used to resolve supertypes without causing classloading side effects.

### Minimal Mixin service bootstrapping

MixinTale ships a minimal `IMixinServiceBootstrap` + `IMixinService` so the environment can integrate with tooling expecting a service, without using a full mixin stack.
