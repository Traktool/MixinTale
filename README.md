# MixinTale

MixinTale is a Java 21+ patching framework inspired by Harmony, designed for Hytale early-loading environments.

## Modules

- `api`: public annotations and `Operation<R>`.
- `processor`: annotation processor generating deterministic `mixintale.index.json`.
- `core`: pure runtime engine (ASM/Gson only) with index loading, safe frame computation, weaving, and reporting.
- `bootstrap`: EarlyPlugin-facing integration, mod jar lookup, Mixin service declarations, and shutdown report flush.

## Install (server/user)

1. Build `:bootstrap:shadowJar`.
2. Install the generated bootstrap jar as your EarlyPlugin artifact.
3. Keep mod jars in `mods/` (override via `-Dmixintale.modsDir=/path/to/mods`).
4. Optional strict mode: `-Dmixintale.failHard=true`.

## Mod developer setup

Use in mod projects:

- `compileOnly(project(":api"))`
- `annotationProcessor(project(":processor"))`

The processor outputs `mixintale.index.json` into class output, consumed at runtime by the bootstrap/core.

## Packaging note

`bootstrap` uses Shadow with `mergeServiceFiles()` so `META-INF/services/*` providers survive shading (critical for Mixin ServiceLoader resolution).
