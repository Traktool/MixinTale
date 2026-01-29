# MixinTale – Harmony‑like Patching for Hytale (Java 25)

> **MixinTale** brings a **Harmony (C#) style** patching experience to **Hytale** mods in Java:
> **Prefix / Postfix / Replace** using **simple annotations**, with **compile‑time validation** and a **runtime weaver** that does **not leak Mixin/CallbackInfo** into game classes.

This document is intended to be a **full GitHub wiki in one file**: practical, explicit, and example‑heavy.

---

## Quick mental model (Harmony analogy)

| Harmony (C#) | MixinTale (Java) | Meaning |
|---|---|---|
| `[HarmonyPatch]` | `@Patch` | selects the target method |
| `Prefix(...)` | `@Prefix` | runs before original, can optionally **skip** it |
| `Postfix(...)` | `@Postfix` | runs after original, can optionally edit result |
| `return false` in Prefix | `return false` in `@Prefix` | **skip original** |
| `__result = ...` | `@Result AtomicReference<R>` | set/modify return value |

✅ **Yes**: in MixinTale a `@Prefix` **can cancel** (skip original) **and** set a return value (via `@Result`).  
Your example is the intended pattern.

---

## Table of Contents

1. What is MixinTale  
2. Modules (API / Processor / Bootstrap / Doctor)  
3. Installation (Server owners)  
4. Installation (Mod developers)  
5. How patching works (compile‑time → runtime)  
6. Annotations reference  
   - `@Patch`  
   - `@Prefix`  
   - `@Postfix`  
   - `@Replace`  
   - `@This` / `@Arg` / `@Result`  
7. Real examples  
8. Priority & ordering  
9. Logging & debugging (no args required)  
10. Doctor tool  
11. Common mistakes & fixes  
12. FAQ  
13. Design notes (why ASM weaving, why no CallbackInfo)  

---

## 1) What is MixinTale?

MixinTale is a **method‑patching framework** for Hytale that prioritizes:
- **Beginner DX** (same mental model as Harmony)
- **No runtime dependency leaks** (no `CallbackInfo`, no `org.spongepowered.*` required at runtime)
- **Deterministic, safe behavior**
- **Java 25 compatibility**

### What MixinTale is NOT
- It is not “a wrapper around `@Inject`”.
- It does not require server owners to pass JVM flags.
- It does not ask modders to understand Mixin internals.

---

## 2) Modules

### ✅ MixinTale‑Bootstrap (Runtime / Required on servers)
- Runs as **Hytale earlyplugin** (ClassTransformer)
- Scans `/mods` for patch indexes
- Applies patches using an **ASM weaver**

📁 Install: `/earlyplugins/MixinTale‑Runtime‑X.Y.Z.jar`

### ✅ MixinTale‑API (Dev‑time only)
- Annotation API used by mods: `@Patch`, `@Prefix`, `@Postfix`, `@Replace`, …

📦 Add as Gradle dependency (**do not ship as server runtime requirement**)

### ✅ MixinTale‑Processor (Dev‑time only)
- Annotation processor
- Validates patch signatures
- Generates **`mixintale.index.json`** inside your mod jar

### ✅ MixinTale‑Doctor (Optional tool)
- Reads mod jars
- Prints what patches exist, targets, conflicts, missing methods, etc.

---

## 3) Installation (Server owners)

⚠️ **Important – Early plugin**

MixinTale is an **early bootstrap framework** and **must NOT be installed like a regular mod**.

### Installation steps

1. Place the runtime jar here:
   ```
   /earlyplugins/MixinTale‑Bootstrap‑X.Y.Z.jar
   ```

2. Put your mods here:
   ```
   /mods/*.jar
   ```

3. When running a server, you **MUST** add the following launch argument:
   --accept-early-plugins

Without this argument, early plugins will be ignored by Hytale and MixinTale will not load.

❌ Do NOT place MixinTale in the `mods` folder.
That’s it.

---

## 4) Installation (Mod developers)

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    compileOnly("com.traktool:mixintale-api:VERSION")
    annotationProcessor("com.traktool:mixintale-processor:VERSION")
}
```

**Rules:**
- ✅ `compileOnly` + `annotationProcessor`
- ❌ do NOT shade the processor
- ❌ do NOT embed Sponge Mixin callback types in your patch signatures

---

## 5) How patching works (explicit pipeline)

### Compile‑time
1. You write `@Patch` classes in your mod.
2. `MixinTale‑Processor` validates them.
3. The processor writes a file **inside your mod jar**:
   - `mixintale.index.json`

### Runtime
1. `MixinTale‑Bootstrap` scans `/mods` and reads all `mixintale.index.json`.
2. For each target class it loads, it **weaves bytecode**:
   - It injects calls to your Prefix/Postfix/Replace handlers.
   - It **copies handler bytecode into the target class** so the game never needs to load your patch framework classes.

**Key guarantee:** Game classes never depend on `org.spongepowered.*` or `com.traktool.mixintale.*` at runtime.

---

## 6) Annotations reference (ULTRA explicit)

### 6.1 `@Patch`

Declares a patch class targeting a specific class/method.

```java
@Patch(
    target = ItemStack.class,
    method = "withRestoredDurability",
    desc   = "(D)Lcom/hypixel/hytale/server/core/inventory/ItemStack;",
    priority = 1000
)
public final class ItemStackPatch { ... }
```

**Fields**
- `target` (required): target class
- `method` (required): method name
- `desc` (required): JVM descriptor (avoids overload ambiguity)
- `priority` (optional): ordering when multiple patches exist

> Why `desc` is required: Hytale has overloads. Names alone are not unique. Descriptor makes patching deterministic.

---

### 6.2 `@Prefix`  ✅ (CAN skip + CAN modify return)

**Runs before the original method.**  
Signature rules (Harmony-like):

#### Option A: “Observe only” prefix
- Return type: `void`
- Cannot skip original

```java
@Prefix
public static void prefix(@This ItemStack self) {
    // do something
}
```

#### Option B: “Harmony-like cancel” prefix (recommended)
- Return type: `boolean`
- Return `true` → run original  
- Return `false` → **skip original**
- If the target method returns non-void, you can provide `@Result AtomicReference<R>`

```java
@Prefix
public static boolean prefix(@This ItemStack self,
                             @Result java.util.concurrent.atomic.AtomicReference<ItemStack> result) {
    result.set(/* replacement */);
    return false; // skip original
}
```

✅ This is the pattern you posted. It is correct, supported, and the intended DX.

**Important for non-void targets**
- If you return `false` you MUST ensure the result is set (or accept returning null if the return type allows it).

---

### 6.3 `@Postfix` ✅ (can edit return)

Runs **after** the original method.

- Return type: `void`
- Can take `@Result AtomicReference<R>` to read/modify the result

```java
@Postfix
public static void postfix(@Result java.util.concurrent.atomic.AtomicReference<ItemStack> result) {
    ItemStack current = result.get();
    result.set(current); // or modify
}
```

---

### 6.4 `@Replace` ✅ (complete override)

Completely replaces the method body.

- Same signature as target (plus optional `@This`)
- No original execution

```java
@Replace(method="withRestoredDurability", desc="(D)Lcom/.../ItemStack;")
public static ItemStack replace(@This ItemStack self, double maxDurability) {
    return new ItemStack(...);
}
```

When to use `@Replace` vs cancel-prefix?
- `@Replace` is clearest when you truly want a full override.
- cancel-prefix is great when you want conditional override (sometimes run original, sometimes not).

---

### 6.5 Parameter mapping annotations

#### `@This`
Receives the instance for non-static methods.

```java
@Prefix
public static void prefix(@This ItemStack self) { }
```

#### `@Arg(n)`
Receives argument *n* from the target call (0-based).

```java
@Prefix
public static void prefix(@Arg(0) double maxDurability) { }
```

#### `@Result`
For non-void targets, use:

```java
@Result AtomicReference<ReturnType>
```

Why `AtomicReference`?
- It is a **java.*** type, always visible.
- It provides a “by-ref” container like Harmony’s `ref __result`.

---

## 7) Real example (your exact use-case)

### Goal
When repairing an item, Hytale reduces max durability.  
We want to **ignore that penalty**.

### Patch (cancel-prefix + forced return)

```java
@Patch(
    target = ItemStack.class,
    method = "withRestoredDurability",
    desc   = "(D)Lcom/hypixel/hytale/server/core/inventory/ItemStack;"
)
public final class ItemStackPatch {

    @Prefix
    public static boolean prefix(
            @This ItemStack self,
            @Result java.util.concurrent.atomic.AtomicReference<ItemStack> result
    ) {
        double forcedMax = self.getMaxDurability();

        ItemStack replaced = new ItemStack(
                self.getItemId(),
                self.getQuantity(),
                forcedMax,
                forcedMax,
                self.getMetadata()
        );

        result.set(replaced);
        return false; // skip original
    }
}
```

**What happens at runtime**
- Prefix runs
- It sets the return value container
- It returns false → original code is skipped
- Target method returns `result.get()`

---

## 8) Priority & ordering

```java
@Patch(priority = 1000)
```

- Higher priority runs first.
- Deterministic across reboots.
- If multiple cancel-prefixes return false, the highest priority one wins (because original is skipped early).

---

## 9) Logging & debugging (optional; never required)

MixinTale prints a useful boot summary by default:
- number of mods scanned
- number of jars with index
- number of patches
- number of target classes

Optional flags (advanced):
- `-Dmixintale.log.patches=true` → prints patch entries (index contents)
- `-Dmixintale.debug.transforms=true` → logs each class actually patched
- `-Dmixintale.debug.index=true` → logs index parsing issues

**No flag is required to make it work.**

---

## 10) Doctor tool

Purpose: help server owners/modpack authors understand what will patch what.

Example:
```bash
java -jar MixinTale-Doctor.jar mods/
```

Outputs:
- patch count
- target classes
- missing methods (bad desc)
- conflicts (multiple patches same target)

---

## 11) Common mistakes & fixes

### Patch not applied
- Wrong `desc`
- Wrong target class
- Mod jar not in `/mods`
- You forgot the annotation processor

### “CallbackInfoReturnable not found” crash
You used Mixin `@Inject` or referenced CallbackInfo types in signatures.

**Fix:** use `@Prefix/@Postfix/@Replace` only and remove Mixin callback types from your mod runtime.

---

## 12) FAQ

**Do I need Sponge Mixin?**  
No. MixinTale does not require Mixin at runtime for MixinTale patches.

**Can I cancel in Prefix?**  
✅ Yes, by returning `boolean` and returning `false` to skip original.

**Can I modify the return value?**  
✅ Yes, using `@Result AtomicReference<R>` in Prefix or Postfix.

**Why AtomicReference?**  
Because it’s always available (`java.*`) and acts like Harmony’s `ref __result`.

---

## 13) Design notes

### Why not CallbackInfo?
Because it leaks a runtime dependency on `org.spongepowered.*` into game classes, which breaks in Hytale’s classloader model.

### Why ASM weaving?
- deterministic
- no Mixin compatibility-level headaches (Java 25)
- no reflective dispatch in hot paths
- no dependency leaks

---

## Appendix: Descriptor cheatsheet

- `void m()` → `()V`
- `ItemStack m(double)` → `(D)Lcom/hypixel/hytale/server/core/inventory/ItemStack;`
- `boolean m(int, String)` → `(ILjava/lang/String;)Z`

Tip: Use your IDE decompiler or ASMifier tools, or copy from generated stubs.

---

## Final note

MixinTale’s promise:

> If it compiles, it runs.  
> If you can write Java, you can patch Hytale.

