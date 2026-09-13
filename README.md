# MixinTale

**A bytecode patching framework for [Hytale](https://hytale.com) mods.** Write a plain static Java
method, annotate it, and it runs inside the game class you targeted — before the JVM has even
defined that class.

```java
@Patch(RepairItemInteraction.class)
public final class RepairPenaltyPatch {

    @RedirectCall(value = "run", target = Item.class, name = "getMaxDurability")
    public static double noPenaltyBase(@This Item item) {
        return 0.0D;
    }
}
```

That is a complete, shipping mod. No JVM descriptors, no refmap, no configuration file, no runtime
library on your class path. The annotation processor resolves the signatures at compile time and
writes a manifest into your jar; the engine applies it at startup.

| | |
|---|---|
| Latest | **3.0.0** |
| Game | Hytale 0.6.x (server class files: Java 25) |
| Runtime | one 468 KB jar in `EarlyPlugins/` — ASM, relocated, and nothing else |
| Build | JDK 25, Gradle 9.1+ |
| Licence | MIT |

### Downloads

| | |
|---|---|
| **MixinTale** — the framework itself. Every player and server running a MixinTale mod needs this one. | [CurseForge](https://www.curseforge.com/hytale/bootstrap/mixintale) |
| **MixinTale — Developer Tools** — the annotation API and the compile-time processor. Mod authors only. | [CurseForge](https://www.curseforge.com/hytale/mods/mixintale-developer-tools) |

*This repository is the documentation. The page you are reading is the whole manual: read it once
top to bottom, then search it.*

---

## Contents

- [Why this exists](#why-this-exists)
- [Installing](#installing-as-a-player)
- [Your first patch](#your-first-patch)
- [How it works](#how-it-works)
- [**The one rule**](#the-one-rule)
- [The primitives](#the-primitives)
  - [`@Patch`](#patch--choosing-a-target) · [`@Prefix`](#prefix--run-before-optionally-cancel) ·
    [`@Postfix`](#postfix--run-after-optionally-rewrite-the-result) ·
    [`@Replace`](#replace--substitute-the-whole-body) ·
    [`@RedirectCall`](#redirectcall--intercept-one-call-site) ·
    [`@Accessor`](#accessor--read-and-write-private-fields) ·
    [`@Invoker`](#invoker--call-private-methods)
- [Binding parameters](#binding-parameters-this-arg-result)
- [Signatures: what you can leave out](#signatures-what-you-can-leave-out)
- [Gating: don't change the game for everyone](#gating-dont-change-the-game-for-everyone)
- [Priority: when two mods meet](#priority-when-two-mods-meet)
- [State in a patch class](#state-in-a-patch-class)
- [Runtime switches and the apply report](#runtime-switches-and-the-apply-report)
- [When a patch stops working](#when-a-patch-stops-working)
- [Surviving a game update](#surviving-a-game-update)
- [What is verified, and how](#what-is-verified-and-how)
- [Limitations, and why](#limitations-and-why)
- [FAQ](#faq)

---

## Why this exists

Hytale's server exposes a class transformer that runs before any game class is defined — the same
hook Fabric and Forge are built on. What it does not give you is a way to *use* it: you get raw
bytes in and raw bytes out, and everything between is your problem.

MixinTale is the layer in between. It is closer to [BepInEx](https://github.com/BepInEx/BepInEx)
and Harmony than to SpongePowered Mixin: your patches are ordinary static methods, the compiler
checks them, and the framework stays out of the way. Where Mixin asks you to learn an injection
DSL, MixinTale asks you to write a method whose parameters say what it wants.

It is also deliberately small. The runtime is one jar carrying a relocated copy of ASM. Nothing
else is on the class path when the game boots, so nothing else can collide with a library the game
already shades.

---

## Installing (as a player)

| File | Goes in |
|---|---|
| `MixinTale-Bootstrap-3.0.0.jar` | `<UserData>/EarlyPlugins/` |
| any mod that uses it | `<UserData>/Mods/` |

`<UserData>` is next to the game — on Windows, typically
`…\Hytale\Hytale Game\UserData\`.

Class transformers are opt-in, and 0.6.x tightened this: a **dedicated server** must be started
with `--accept-early-plugins`, and refuses to start without it when no console is attached.
Singleplayer accepts them automatically. If mods "stopped loading" after updating, this is almost
always why.

You should see this early in the log:

```
[EarlyPlugin] Loading transformer: com.traktool.mixintale.bootstrap.MixinTaleTransformer (priority=1000)
[MixinTale/INFO] MixinTale 3.0.0 ready - 1 patch(es) across 1 class(es) from 1 archive(s).
```

---

## Your first patch

### 1. Build setup

```kotlin
// build.gradle.kts
java {
    // Hytale 0.6.x ships class-file version 69. javac refuses to *read* class files newer than
    // its --release setting, so anything compiling against the game needs 25 or above.
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}
tasks.withType<JavaCompile> {
    options.release = 25
    options.compilerArgs.add("-parameters")
}

val hytale = "D:/Games/Hytale/Hytale Game/install/release/package/game/latest/Server/HytaleServer.jar"

dependencies {
    compileOnly(files("MixinTale-API-3.0.0.jar"))         // annotations only, never shipped
    annotationProcessor(files("MixinTale-Processor-3.0.0.jar"))
    compileOnly(files(hytale))                            // the game, provided at runtime
}
```

Both jars are in `MixinTale-Developer-Tools-3.0.0.zip`. Neither ends up in your mod: the API is
annotations with `CLASS` retention, and the processor only runs at compile time.

### 2. The patch

```java
package com.example.mymod.patches;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.traktool.mixintale.api.*;

@Patch(ItemStack.class)
public final class DoubleDurabilityPatch {

    private DoubleDurabilityPatch() {
    }

    @Postfix("getMaxDurability")
    public static double doubled(@This ItemStack self, @Result double original) {
        return original * 2.0D;
    }
}
```

### 3. Build

Your jar now contains a generated `mixintale.index.json`:

```json
{
  "formatVersion": 2,
  "generator": "MixinTale-Processor/3.0.0",
  "patches": [{
    "patchClass": "com/example/mymod/patches/DoubleDurabilityPatch",
    "targetClass": "com/hypixel/hytale/server/core/inventory/ItemStack",
    "priority": 1000,
    "actions": [{
      "kind": "POSTFIX",
      "handler": "doubled",
      "handlerDescriptor": "(Lcom/hypixel/hytale/server/core/inventory/ItemStack;D)D",
      "params": ["this", "result"],
      "targetMethod": "getMaxDurability",
      "targetDescriptor": "()D"
    }]
  }]
}
```

You never write or read that file. It exists so the engine can decide, for each of the ~40 000
classes the server loads, whether any patch applies — with one hash-map lookup instead of opening
jars.

### 4. A `Main` class (optional but recommended)

A patch-only mod does not strictly need one, but a plugin lets you confirm the patch landed:

```java
public final class MyModPlugin extends JavaPlugin {

    public MyModPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // MixinTale relocates handlers under a "mixintale$" prefix, so their presence is a
        // dependency-free way to check that weaving actually happened.
        boolean patched = java.util.Arrays.stream(ItemStack.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().startsWith("mixintale$"));
        if (!patched) {
            getLogger().at(Level.SEVERE).log("The patch did not apply - check for [MixinTale] lines.");
        }
    }
}
```

Do this. The failure mode of bytecode patching is silence.

---

## How it works

```
  compile time                        runtime (server boot)
  ─────────────────────────────       ──────────────────────────────────────────────
  @Patch class                        EarlyPluginLoader
        │                                   │  ServiceLoader, before the server exists
        ▼                                   ▼
  MixinTaleProcessor                  MixinTaleTransformer
        │  validates, resolves              │
        │  descriptors                      ▼
        ▼                             scan Mods/ → index by target class
  mixintale.index.json ────────────▶        │
  (inside your mod jar)                     ▼  for each class the game defines
                                      relocate the patch class into the target
                                      apply prefix / postfix / replace / redirect
                                      recompute stack map frames
                                            │
                                            ▼
                                      TransformingClassLoader.defineClass
```

The step that shapes everything else is **relocation**. Your handler is not *called* from the game
class — it is *moved into* it. The weaver rewrites every reference to your patch class into a
reference to the target and renames each member under a per-patch prefix
(`mixintale$3f9c1a2b$doubled`), then installs those members on the target.

That buys three things at once: handlers can touch private state, no reference crosses a class
loader boundary, and two mods patching the same class cannot collide. It costs exactly one
constraint.

---

## The one rule

> **A handler may only mention JDK types, `com.hypixel.hytale.*` types, and members of its own
> patch class.**

Your handler runs inside the game's class loader, which has never heard of your mod. Referencing
one of your own classes compiles fine and throws `NoClassDefFoundError` the first time the patched
method runs — in someone else's game, weeks later.

So the annotation processor refuses it at build time:

```
error: [MixinTale] The parameter 'config' refers to com.example.mymod.MyConfig, which the game's
class loader cannot see. Handler bodies are relocated into the target class, so they may only
mention JDK types, com.hypixel.hytale types, or members of this patch class.
```

The check covers signatures, not bodies. A handler that constructs one of your classes internally
will still compile and still fail at runtime, so keep handlers small and let the rule police
itself. If you know what you are doing — a shared package that you also relocate, for instance —
widen it:

```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Amixintale.allowedPackages=com.example.shared")
}
```

### Processor options

| Option | Effect |
|---|---|
| `-Amixintale.allowedPackages=a.b,c.d` | extra package prefixes a handler signature may reference |
| `-Amixintale.verbose=true` | confirm what was generated; silent otherwise |

The processor prints nothing on a successful build by design. A line saying "generated the file I
was asked to generate" is noise in every log forever, and Gradle's problems report lists compiler
notes as findings — so a green build would look like it had warnings. Failures are always
reported, and a build that cannot write the index fails outright: a mod whose index is missing
loads fine and patches nothing.

**Passing data in and out.** The usual pattern is a `static volatile` field of a JDK type on the
patch class itself, written by your plugin through a small setter that is *also* on the patch
class… except your plugin cannot call it either, for the same reason. In practice: read a system
property, or gate on values that already exist in the game (see
[Gating](#gating-dont-change-the-game-for-everyone)).

---

## The primitives

Pick the least invasive one that does the job. The order below is also the order of how likely
your mod is to survive the next game update.

| | What it does | Reach for it when |
|---|---|---|
| [`@RedirectCall`](#redirectcall--intercept-one-call-site) | replaces one invocation inside a method | you want to change an *input* to logic that is otherwise fine |
| [`@Postfix`](#postfix--run-after-optionally-rewrite-the-result) | runs at every `return`, can rewrite the value | you want to adjust a *result* |
| [`@Prefix`](#prefix--run-before-optionally-cancel) | runs first, can skip the body | you want a guard or an early exit |
| [`@Replace`](#replace--substitute-the-whole-body) | substitutes the body | nothing else fits |

Plus two helpers that exist only to serve the handlers above:
[`@Accessor`](#accessor--read-and-write-private-fields) and
[`@Invoker`](#invoker--call-private-methods).

---

### `@Patch` — choosing a target

```java
@Patch(ItemStack.class)                                   // preferred: the compiler checks it
@Patch(value = ItemStack.class, priority = 2000)          // higher runs first
@Patch(className = "com.hypixel.hytale.…")                // for targets not on the compile path
```

The class literal is checked by the compiler and survives a rename in your IDE. Use `className`
only for package-private or otherwise unreachable classes. Nested classes work with either form —
`@Patch(PluginManifest.ServerVersionCheck.class)` resolves to
`com/hypixel/hytale/common/plugin/PluginManifest$ServerVersionCheck`.

A patch class must be `final`, must declare no instance members, and is never instantiated. Give
it a private constructor and treat it as a description, not an object.

---

### `@Prefix` — run before, optionally cancel

```java
@Prefix("withIncreasedDurability")
public static boolean ignoreTinyRepairs(@This ItemStack self, @Arg(0) double amount) {
    return amount >= 1.0D;        // false → the original body is skipped
}
```

Return type decides control flow:

- **`void`** — always continue into the original body. Use it to observe.
- **`boolean`** — `false` skips the original body entirely.

When you cancel, the method returns the default value for its type (`0`, `false`, `null`) unless
you supply one through a `@Result` **one-element array**:

```java
@Prefix("getMaxDurability")
public static boolean pinned(@This ItemStack self, @Result double[] result) {
    result[0] = 100.0D;
    return false;                 // getMaxDurability() now returns 100.0
}
```

The array is not a stylistic choice. A wrapper type would have to be loadable from the game's class
loader, and no MixinTale class is; arrays are a JVM primitive and always resolve.

Prefixes cannot be injected into constructors or static initialisers — the receiver is not yet
initialised there, and the JVM would reject the result.

---

### `@Postfix` — run after, optionally rewrite the result

```java
@Postfix("getDisplayName")
public static Message goldWhenPristine(@This ItemStack self, @Result Message original) {
    return self.getDurability() < self.getMaxDurability() ? original : original.color("#ffd700");
}
```

- Return **`void`** to observe without changing anything.
- Return **the target's return type** to replace the value.

Declare `@Result` to see what is about to be returned. The handler runs at *every* `return` in the
method, so a method with five exit points calls it five times.

---

### `@Replace` — substitute the whole body

```java
@Replace("withRestoredDurability")
public static ItemStack keepMaxDurability(@This ItemStack self, @Arg(0) double ignored) {
    double max = self.getMaxDurability();
    return new ItemStack(self.getItemId(), self.getQuantity(), max, max,
                         self.getQualityIndex(), self.getMetadata());
}
```

The handler's return type must match the target's exactly.

**This is the primitive that ages worst**, and the mod that used to live in this repository is the
cautionary tale. Version 1.2.2 replaced exactly the method above and rebuilt the stack with a
hard-coded five-argument constructor. Hytale 0.6.x added `qualityIndex` to that constructor. The
patch still applied cleanly — and silently erased item quality on every repair. A one-line
`@RedirectCall` replaced it and copies no game logic at all.

Note that the example above already compiles with a deprecation warning: `getMetadata()` is on its
way out. A `@Replace` handler has to keep up with every member the original body touches, forever.

Reach for `@Replace` last. It also makes your mod incompatible with any other mod that replaces the
same method.

---

### `@RedirectCall` — intercept one call site

The surgical one. Instead of rewriting a method, you intercept one invocation it makes.

```java
// RepairItemInteraction.run computes:
//   newMax = floor(max - itemStack.getItem().getMaxDurability() * (penalty * ratioRepaired));
// Reporting the item type's base durability as 0 makes the whole penalty term vanish.

@RedirectCall(value = "run", target = Item.class, name = "getMaxDurability", require = 1)
public static double noPenaltyBase(@This Item item) {
    return 0.0D;
}
```

- `value` — the method that *contains* the call site.
- `target` / `name` — the method being called. (`ownerName = "…"` for owners off the compile path.)
- `ordinal` — which matching call site, counting from `0` in bytecode order. `-1`, the default,
  redirects all of them.
- `require` — how many sites must match for the patch to count as applied. See
  [When a patch stops working](#when-a-patch-stops-working).

The handler takes the invocation receiver as `@This` (omit it for a `static` callee), then one
`@Arg` per argument, and returns what the call should evaluate to.

Because the handler body is relocated into the target class, **it can perform the original call
itself** — which makes this a superset of a "wrap" injection. You decide whether, when, and with
what arguments the original runs:

```java
@RedirectCall(value = "compareTo", target = Long.class, name = "compare", ordinal = 1)
public static int lenientMinor(@Arg(0) long left, @Arg(1) long right) {
    if (left == SENTINEL) {
        return 0;
    }
    return Long.compare(left, right);   // the original call, on your terms
}
```

---

### `@Accessor` — read and write private fields

Handlers run inside the target, so they *can* touch its private state — Java just will not let you
write the expression. `@Accessor` generates it. Declare the method, give it a body that throws, and
call it from your handlers.

```java
@Accessor("durability")                                    // getter
private static double durability(@This ItemStack self) { throw new AssertionError(); }

@Accessor("durability")                                    // setter: void + a value parameter
private static void durability(@This ItemStack self, double value) { throw new AssertionError(); }

@Accessor("EMPTY")                                         // static field: no @This
private static ItemStack empty() { throw new AssertionError(); }
```

Getter and setter may share a name; they are told apart by their signatures. Field type is inferred
(`descriptor = "…"` overrides it).

A setter on a `final` field is not possible — the JVM only permits assigning a final field from its
declaring class's own `<init>`/`<clinit>`.

---

### `@Invoker` — call private methods

```java
@Invoker("postDecode")                                     // private instance method
private static void postDecode(@This InventoryComponent self) { throw new AssertionError(); }

@Invoker("parseComponent")                                 // private static method: no @This
private static long parseComponent(String value, String part, String original) {
    throw new AssertionError();
}
```

(The first belongs in a `@Patch(InventoryComponent.class)`, the second in a `@Patch(Semver.class)` —
an invoker only reaches methods of its own patch's target.)

The weaver picks `invokespecial`, `invokevirtual` or `invokestatic` by looking at the real method
on the target, so you do not have to.

**Both helpers are only callable from inside the same patch class.** They are relocated with it and
never exist as methods your plugin can call — there is no way to expose them across the class
loader boundary. If you need private state in your plugin, surface it through a patched method that
returns a JDK type.

---

## Binding parameters: `@This`, `@Arg`, `@Result`

Every handler parameter carries exactly one of these. Together they tell the weaver what to push
before calling you — and, in most cases, they are also how it works out the target's signature.

| | Binds to | Notes |
|---|---|---|
| `@This` | the receiver — `this` in the target, or the receiver of the intercepted call | must be the first parameter; omitting it declares the target `static` |
| `@Arg(n)` | argument `n`, counting from `0`, ignoring the receiver | |
| `@Result` | the return value | a value in a `@Postfix`, a **one-element array** in a `@Prefix` |

`@Accessor` and `@Invoker` are the exception: only `@This` is meaningful there, and the remaining
parameters are the field's new value or the callee's arguments, in order.

---

## Signatures: what you can leave out

Normally, everything. The processor infers the target's descriptor from your handler: `@Arg`
parameters give the parameter types, and the return type comes from the handler (`@Replace`) or
from `@Result` (`@Postfix`, `@Prefix`).

Two cases need help.

**Overloads that inference cannot separate.** `Semver.fromString` exists as `(String)` and
`(String, boolean)`. A handler that binds no argument gives the processor nothing to work with:

```java
@Postfix(value = "fromString",
         descriptor = "(Ljava/lang/String;)Lcom/hypixel/hytale/common/semver/Semver;")
public static Semver counted(@Result Semver original) {
    return original;
}
```

Leave `descriptor` out and the build fails with the candidates listed:

```
Method 'fromString' is ambiguous on com/hypixel/hytale/common/semver/Semver
((Ljava/lang/String;)L…Semver;, (Ljava/lang/String;Z)L…Semver;).
Set descriptor = "…" on the annotation to pick one.
```

**Skipping an argument.** If your `@Arg` indexes are not contiguous from `0`, the parameter list
cannot be reconstructed. Bind them all, or state `descriptor` explicitly.

Compiler-generated bridge methods are never candidates. A class implementing `Comparable<Foo>`
carries both `compareTo(Foo)` and a synthetic `compareTo(Object)`; `@Prefix("compareTo")` targets
the real one without ceremony.

---

## Gating: don't change the game for everyone

A patch applies to *every* instance of the class, forever. Most of the time that is what you want.
When it is not, gate on something only your case produces.

**Gate on a sentinel value already in the data.** The cleanest option when the target carries an
identity:

```java
@Postfix("getWebsite")
public static String probe(@This PluginManifest self, @Result String original) {
    if (!"MyModProbe".equals(rawName(self))) {
        return original;                       // everyone else is untouched
    }
    return "…";
}
```

Read the gate through an `@Accessor`, not a getter — especially if you also patch that getter,
which would otherwise lie to its own gate.

**Gate on a system property** when the target has no usable identity:

```java
@Replace("values")
public static Check[] values() {
    Check[] all = rawValues();
    return "on".equals(System.getProperty("mymod.probe")) ? new Check[0] : all.clone();
}
```

Nothing sets the property in normal operation, so the game keeps its behaviour; your code sets it
around the one call it wants to change.

Both patterns are used by the [conformance suite](#what-is-verified-and-how), which is why it is safe to
leave installed.

---

## Priority: when two mods meet

Patches on the same class are applied **highest `priority` first** (default `1000`). Members are
namespaced per patch class, so two mods can patch the same method without colliding — but they do
compose, and the order is defined:

- **`@Postfix`** — the higher-priority handler sits closest to the original `return`, so it runs
  first and the lower-priority one sees its output.
- **`@Prefix`** — same reading: the higher-priority handler runs first, and if it cancels, the
  lower-priority ones never run.

Concretely, with two patches on `getPatch()` returning `3`:

| Priority | Handler | Sees | Returns |
|---|---|---|---|
| 2000 | `original * 2` | `3` | `6` |
| 1000 | `original + 1000` | `6` | `1006` |

`@Replace` from either mod discards everything the other would have seen. If you expect company,
use the surgical primitives.

---

## State in a patch class

Static fields are relocated along with the handlers, including a computed initialiser:

```java
private static final AtomicLong CALLS = new AtomicLong();
```

The patch's `<clinit>` is moved into its own method and called from the head of the target's own
`<clinit>`, so it runs exactly once, whatever branching the game's initialiser contains. The
consequence is that your static fields are initialised **before** the target's own — which is
another reason patch classes are expected to be stateless.

`ACC_FINAL` is dropped from relocated fields: the JVM only allows a static final field to be
assigned from its declaring class's `<clinit>`, and the initialiser now lives one call away.

Instance fields are refused outright. Adding one would change the game class's layout.

---

## Runtime switches and the apply report

All read as system properties, so they go on the JVM command line.

| Property | Effect |
|---|---|
| `-Dmixintale.debug` | log every discovery and weaving decision |
| `-Dmixintale.failHard` | stop the server on a failed patch instead of skipping it |
| `-Dmixintale.verify` | run ASM's verifier on each woven class before the JVM sees it |
| `-Dmixintale.report=<path>` | write a JSON report of everything that was applied |
| `-Dmixintale.modsDir=<paths>` | scan these directories instead (comma-separated) |
| `-Dmixintale.ignoreModConfig` | patch even mods the server configuration has switched off |
| `-Dmixintale.priority=<n>` | transformer priority relative to other early plugins |
| `-Dmixintale.disable` | load the transformer but weave nothing |

The report is what a bug report should carry instead of a screenshot:

```json
{
  "meta": { "classesSeen": 41822, "classesWoven": 1, "actionsApplied": 1, "actionsFailed": 0 },
  "environment": { "mixintale": "3.0.0", "java": "26.0.2", "modsDirectory": "mods" },
  "discovered": [ { "archive": "MyMod-1.0.0.jar", "patchClass": "…", "targetClass": "…" } ],
  "applied":    [ { "targetClass": "…", "action": "REDIRECT_CALL run(…) -> …", "sites": 1 } ],
  "failures":   [],
  "unresolvedTypes": []
}
```

`unresolvedTypes` is worth a look if you ever hit a `VerifyError`: a type the engine could not
locate had to be assumed to extend `Object`, and that is the usual root cause.

---

## When a patch stops working

1. Start with `-Dmixintale.debug -Dmixintale.report=mixintale-report.json`.
2. Look for `[MixinTale]` lines: how many patches were discovered, and how many sites each action
   hit.
3. Read the report's `failures`. The messages are written to be actionable:

```
REDIRECT_CALL run(?) -> com/hypixel/…/Item.getMaxDurability()D matched 0 site(s) but require = 1.
The game method has most likely changed in this version.
```

```
No method named 'withRestoredDurability' on com/hypixel/…/ItemStack.
Present methods: cleanCopy, getBlockKey, getDurability, getItem, getItemId, …
```

4. `-Dmixintale.verify` runs ASM's analysing verifier before the JVM does, turning a bare
   `VerifyError` with an offset into a readable dump of the offending method.

### Two things that look like a broken patch and are not

**`0 patch(es) across 0 class(es) from 0 archive(s)`** means no mod archive with a
`mixintale.index.json` was found. The engine scans `./mods` plus every directory the server was
given as `--mods`, which is what the server itself does. If your mods live somewhere else entirely,
point at it with `-Dmixintale.modsDir`.

**`Skipping mod <id> (Disabled by server config)`** is the game, not MixinTale. A world's
`config.json` carries a `Mods` map:

```json
"Mods": {
  "com.example:MyMod": { "Enabled": true }
}
```

A mod set to `false` there is switched off for that world — and MixinTale skips its patches too,
because a mod you unticked should not still be rewriting the game's bytecode. Enable it in the mod
list when you load the world, or flip the flag in that file. `-Dmixintale.ignoreModConfig` forces
the patches on regardless, which is occasionally useful and usually a mistake.

**`<mod>.jar was built against MixinTale 2.x (index format v1)`** means exactly what it says. The
2.x index named every field of an action differently, so 3.x cannot read it — the mod has to be
rebuilt against the 3.x API. The mod still loads and still runs; only its patches are skipped, and
no other mod is affected.

**`require` is the setting that matters here.** It defaults to `1`, so an injection that matches
nothing is an error rather than a no-op. Without `failHard` the engine logs it, records it and
loads the class unmodified — your mod degrades to "does nothing" rather than "server will not
boot", but never to "nobody noticed". Set `require = 0` for an injection you genuinely expect to
miss on some versions.

---

## Surviving a game update

Two tools, both in this repository.

**`./gradlew weaveCheck`** takes the jars your build produces and a real `HytaleServer.jar`, weaves
the actual game classes, asks the JVM to define them, and then calls the woven methods to check they
behave. Wire it into `check` and a game update that moved a method fails your build instead of a
player's world:

```
  ok    wove RepairItemInteraction = true
  ok    RepairItemInteraction | REDIRECT_CALL run(?) -> …Item.getMaxDurability()D applied at 1 site(s)
  ok    RepairItemInteraction passes JVM verification and carries the patch = true
weaveCheck: 181 check(s) passed.
```

**The API index** dumps every class, field and method signature in the game as flat text, so you
can diff two versions:

```bash
python3 tools/api-index/class_index.py HytaleServer.jar com/hypixel/hytale/ api-0.6.x.txt
diff api-0.6.x.txt api-0.7.0.txt | less
```

It parses class files directly rather than shelling out to `javap`, which refuses class files newer
than the JDK running it — and Hytale's usually are.

---

## What is verified, and how

MixinTale's release gate weaves **every primitive in every shape** into classes that ship in the
game, has the JVM define and verify the result, and then calls the woven methods to check they
actually behave:

```
  ok    conformance: @Prefix cancels the body and returns result[0] (42) = true
  ok    conformance: @Replace on an overload taking a 2-slot argument (1592590339) = true
  ok    conformance: @RedirectCall(ordinal = 1) boxed only the minimum = true
  ok    conformance: @RedirectCall(ordinal = 0) on an invokeinterface call (256) = true
  ok    conformance: @Postfix on a final method (false) = true
  ok    conformance: the neighbouring non-final getter is untouched (true) = true
weaveCheck: 181 check(s) passed.
```

This matters to you for one reason: it is the list of shapes you can rely on. If your patch has the
same shape as a row below, it is not the framework you are debugging.

### What is covered, and where

Each behaviour is checked twice — once on an instance the suite marked with a sentinel value, and
once on an ordinary one — so a patch that leaked outside its gate fails the build.

| Game class | Why it | Primitives exercised |
|---|---|---|
| `Semver` | pure value type, no server needed | `@Prefix` (void, cancelling, with `@Result`), `@Postfix` (void, rewriting, explicit descriptor), `@Replace`, `@RedirectCall` (by ordinal, optional `require = 0`), `@Accessor` (array field), `@Invoker` (private static), relocated static state and `<clinit>`, bridge-method exclusion |
| `Semver` ×2 patches | two priorities on one method | postfix composition order |
| `DiscreteValueRecorder` | benchmark helper, never on a hot path | `void` target, `long` parameters and fields (two JVM slots), overloads resolved by explicit descriptor, `@Replace` on an overload, `@RedirectCall` on a self-call, `@RedirectCall(ordinal = 1)` picking one of three calls to `Long.valueOf`, `@Accessor` on a static array constant, `@Invoker` on a public method |
| `Int3OpenHashSet` | self-contained data structure | `@Accessor` get and set, instance and static, `final` and mutable; `@Invoker` instance and static |
| `Range` | two `float` fields | `float` return type, `@Postfix`, `@Replace`, accessor write-and-read-back |
| `BoolIntPair` | `final` methods and a static factory | `@Postfix` on a `final` method, `@Postfix` on a static factory, `@Prefix` cancelling with no `@Result` |
| `BitUtil` | every member is `static` | static targets with no receiver, `byte` return, array parameter, `void` static postfix |
| `Flags` | calls a method through an interface | `@RedirectCall` on an `invokeinterface` call site, ordinal `0` vs `1` |
| `PluginManifest` | mutable reference fields | `@Patch(className = "…")`, `@Accessor` setter |
| `PluginManifest.ServerVersionCheck` | nested enum, `$` in its binary name | nested target class, `@Replace` on a `static` method, accessor on the synthetic `$VALUES` |

### What the fixtures cover

A handful of shapes have no convenient home in the game's own classes, so the suite carries its own:

- a cancelling `@Prefix` on a method of **every** return type — `void`, `boolean`, `byte`, `char`,
  `short`, `int`, `long`, `float`, `double`, a reference and an array — because the default value a
  cancellation has to push is a different instruction for each, and the 64-bit ones are where frame
  computation goes wrong;
- `@Replace` on a `void` method;
- `@RedirectCall(require = 2)` satisfied by two call sites;
- **three** patches at three priorities on one method: postfixes composing `1 → ×2 → +3 → ×5 = 25`,
  and prefixes competing where only the highest-priority cancellation is observed;
- a handler containing a **lambda**, a **switch expression**, a **try/catch** and **varargs**,
  because each compiles to something that has to survive relocation — a lambda becomes an
  `invokedynamic` whose bootstrap handle points at a synthetic method that moves with it.

None of this needs a running Hytale process, and none of it ships: it is a build step, and the only
thing a release produces is the bootstrap jar.

## Limitations, and why

**No `@WrapCall` / `Operation`.** A wrap injection needs a shared interface that both your mod and
the woven code can load — and no MixinTale class is visible from the game's class loader, by
construction. `@RedirectCall` covers the same ground: its body is relocated into the target, so it
can perform the original invocation itself, conditionally.

**You cannot patch mod classes.** The transformer runs inside Hytale's `TransformingClassLoader`,
which loads the game. Mods are loaded later by `PluginClassLoader` and never pass through it. A
`@Patch` on another mod's class silently matches nothing.

**No injection into constructors or static initialisers.** `<init>` has an uninitialised receiver
before the `super()` call and the JVM rejects the result; `<clinit>` is used for relocated state.

**No local-variable capture.** You get the receiver, the arguments and the return value. Reading a
local halfway through a method needs a bytecode-offset DSL, which is exactly the complexity this
framework exists to avoid — `@RedirectCall` on whatever produced that local is usually the better
patch anyway.

**`@Accessor` and `@Invoker` are not an API for your plugin.** They are relocated into the target
and only exist there.

---

## FAQ

**Do my users need anything besides my mod?**
`MixinTale-Bootstrap.jar` in `earlyplugins/`. Declare it in your mod page's requirements; there is
no dependency mechanism for early plugins.

**Does the API jar ship inside my mod?**
No. The annotations have `CLASS` retention and the engine reads the generated index, never
annotations. `compileOnly` is correct.

**What happens if two mods patch the same method?**
They compose in priority order — see [Priority](#priority-when-two-mods-meet). Members are
namespaced per patch class, so nothing collides at the bytecode level. `@Replace` is the exception:
it discards whatever the other mod would have seen.

**Can I patch client classes?**
No. The transformer is a server-side early plugin. `HytaleClient.exe` is native.

**Why Java 25 and not something older?**
Because `javac` will not read class files newer than its `--release` setting, and the game's are
version 69. This is a floor imposed by the game, not a preference. Gradle itself must be 9.1 or
later to *run* on Java 25.

**Is it safe?**
It rewrites the game's bytecode before the JVM sees it; Hytale prints a warning banner about
exactly that, and it is right to. Everything MixinTale does is inspectable — `-Dmixintale.report`
tells you precisely which method of which class every installed mod changed.

**How do I uninstall it?**
Delete the jar from `earlyplugins/`. Mods that depend on it will log that their patch did not
apply, and otherwise load normally.

---

## Issues, questions, source

MixinTale is MIT licensed. Bug reports and questions belong in
[Issues](https://github.com/Traktool/MixinTale/issues) — please include:

- the MixinTale version and the Hytale build,
- the `[MixinTale]` lines from your log,
- the file produced by `-Dmixintale.report=mixintale-report.txt` when a patch applied but behaved
  wrongly.

The source lives in a Gradle monorepo alongside the mods built on it and the conformance suite, and
is shared readily on request — it simply is not what this repository is for.

---

*Documentation for MixinTale 3.0.0 against Hytale 0.6.x build 26. Last reviewed 12 September 2026.
Every Java example on this page is compiled against the real game classes before publication.*
