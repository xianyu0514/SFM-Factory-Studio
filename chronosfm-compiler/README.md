# ChronoSFM Compiler

A standalone research compiler/runtime core for **Super Factory Manager** logistics programs.

## Goal

Preserve SFM's observable per-tick logistics semantics and throughput while moving steady-state planning cost away from total network size and toward **actual changes + unavoidable commits**.

The compiler is intentionally Minecraft-independent. No `net.minecraft.*`, Forge, NeoForge, Fabric, ItemStack, BlockEntity, or SFM runtime class may enter this module.

## v0.1 implemented

- Lightweight timer-trigger compilation (local/global tick alignment).
- Exact source-order `TransferRegion` IR.
- Conservative legacy fallback for opaque triggers/statements.
- Label/resource dependency index.
- Incremental invalidation engine using compact region bitsets.
- Exact-order transfer plan view.
- Differential-friendly immutable model objects.
- Unit tests and a compiler scaling smoke benchmark.

## Correctness invariants

1. `EVERY 1 TICK` remains every tick.
2. Optimized execution must never lower legacy logical throughput.
3. Unknown semantics remain on the legacy path.
4. Planning may be parallel later; Minecraft capability commits may not be performed off-thread.
5. Optimization may be disabled without changing program results.

## Planned integration

```
SFM AST / events
      |
      v
SFM Adapter
      |
      v
ProgramModel  ---> ChronoSfmCompiler
                       |
                       +--> CompiledTrigger[]
                       +--> TransferRegion[]
                       +--> DependencyIndex
                       |
                       v
                 Incremental Runtime
                       |
                  exact ordered plan
                       |
                       v
                 SFM legacy commit
```

The first SFM-side integration should address upstream issue #602 by probing timer/redstone eligibility before constructing the expensive full ProgramContext, while keeping `EVERY 1 TICK` behavior unchanged.

## Build

From this directory:

```bash
../gradlew -p . test
../gradlew -p . runCompilerBenchmark
```

When extracted to a standalone repository, add a Gradle wrapper or run with any compatible Gradle installation.
