# SFM integration contract

## Purpose

Integrate ChronoSFM without changing SFM observable logistics semantics.

The first integration target is the current SFM execution order:

1. `ManagerBlockEntity.serverTick()`
2. `Program.tick(manager)`
3. construct `ProgramContext`
4. evaluate `Trigger.shouldTick(context)`
5. execute statements
6. clear redstone pulse queue

Upstream issue #602 documents that step 3 currently occurs before the trigger can decide that a timer is inactive.

## Stage A — safe trigger gate

The SFM adapter converts trigger metadata to `ProgramModel` when a program is parsed/cached.

At runtime:

```java
var probe = compiledProgram.triggerProbe().probe(manager.getTick(), level.getGameTime());

if (probe.maySkipFullContext()) {
    // Only legal when every trigger is proven inactive.
    // Any opaque/custom trigger is conservatively considered due.
    manager.clearRedstonePulseQueue();
    return false;
}

var context = new ProgramContext(program, manager, new ExecuteProgramBehaviour());
// execute due triggers in original source order
```

Important: redstone/custom triggers must remain LEGACY until their preflight semantics are independently proven.

## Stage B — exact-order transfer plan

Compile only transfer statements that are safe to represent structurally. Preserve the original trigger index, statement index and exact-order ordinal.

The initial executor must still call SFM's existing `InputStatement`, `OutputStatement`, trackers and ResourceType capability operations. ChronoSFM only removes repeated structural discovery.

## Stage C — endpoint index

Build semantic indexes above SFM's existing CableNetwork capability cache:

- label -> endpoint ids
- resource type -> endpoint ids
- endpoint -> dependent region ids
- chunk/network revision -> endpoint availability

Do not build a competing raw capability cache.

## Invalidation

Any event that cannot be observed reliably forces the affected endpoint/region to OPAQUE mode. Never trust stale shadow state.

Required invalidations include program replacement, label changes, cable placement/removal, chunk load/unload, block entity replacement and adapter-specific capability invalidation.

## Non-negotiable fallback

If a region contains unsupported control flow, unknown trigger behavior, unknown ordering semantics, or a third-party capability that cannot be safely cached, execute that region through the existing SFM path.
