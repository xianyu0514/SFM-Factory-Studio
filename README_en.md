<div align="center">

# SFM Factory Studio

**Visual factory programming companion for [Super Factory Manager 4](https://github.com/TeamDman/SuperFactoryManager)**

Build automation with blocks — edits side-by-side with SFML source, always saves standard SFML.

[简体中文](README.md) | **English**

</div>

---

- **Minecraft**: 1.21.1 (NeoForge) & 1.20.1 (Forge)
- **Dependency**: [Super Factory Manager 4.34.0](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager) (required)
- **Version**: 0.9.0
- **License**: MPL-2.0

[Downloads](#installation) · [Features](#features) · [Installation](#installation) · [Building](#building)

---

## What is this?

A visual block editor that replaces SFM's text-only program editing. Design factory automation with drag-and-drop blocks, see the SFML source update in real time, and save directly to the manager's disk — no coding required.

The UI follows your Minecraft language setting: **English** or **简体中文**.

## Features

### Block Editor

- **Free-form trigger cards**: infinite canvas, scroll to zoom, drag anywhere, middle/right-click to pan
- **Layout persistence**: card positions saved per trigger (matched by fingerprint, survives edits)
- **Block palette**: timer/redstone triggers, input/output/forget, if/else-if/else with and/or/not grouping, comments, one-click templates (smelting line, full-stack sorting, round-robin, parallel transfers)
- **Extension blocks**: or-move, also-move, quantity limit, retain minimum, resource tag filter, exclude, sides, slots, round-robin, per-block handling, empty slots only
- **Box select & batch ops**: rubber-band select, group drag, copy/paste/duplicate/delete, save as custom template
- **Right-click menus**: block row → copy; label pill → copy/paste/edit/clear; resource slot → copy/paste/browse
- **Undo/redo** (Ctrl+Z / Ctrl+Y), card positions preserved across undo
- **Zone workspaces**: draw colored rectangles on canvas to group related cards, click to focus
- **Card & If folding**: collapse cards to a summary bar, collapse If to one condition line
- **LOD**: distant cards auto-switch to summary rows, hundreds of cards stay smooth
- **Block connections**: drag ◆ from card corner to another card for a visual bezier link (cosmetic only)

### SFML Source Editor

- Blocks and source are **always in sync** — click "Source" to see both side by side
- Syntax highlighting, line numbers, Tab indent, Ctrl+/ comment, smart suggestions (Ctrl+Space)
- **Lossless round-trip**: comments, empty else branches, and formatting survive block↔source conversion
- Validated against all 8 official SFM example programs + 1000 randomized block combinations

### Code editor controls

- Drag the divider above the source pane to resize the split view. Small panes automatically use the code view; **Back to blocks** returns to the canvas.
- **Focus code** / **Split view** or **F7** switches the code workspace. **Wrap lines** / **Alt+Z** changes display wrapping without modifying SFML or line numbers.
- Drag either scrollbar; use **Shift + wheel** to scroll horizontally with wrapping off. Resizing preserves the editor instance, selection and undo history when the source is unchanged.
- **Ctrl+F** finds exact, case-sensitive text; **F3 / Shift+F3** finds the next / previous match and wraps around. **Ctrl+G** jumps to a line.
- **Ctrl+Space** requests suggestions; the configured SFM acceptance key applies the selected suggestion. **Tab / Shift+Tab**, **Ctrl+/**, **Ctrl+Z / Ctrl+Y**, and **Ctrl+S** indent, comment, undo/redo, and save.
- Double-click selects a word. Consecutive typing is grouped for undo. The footer shows the real line and column; view settings are saved per manager.

Ctrl+H opens literal find and replace, with replace-next, a counted replace-all action, and one-step undo. Click compiler errors in the footer to select and locate an error with a source position. Scrollbar tracks remain visible and dim when content fits.

### Diagnostics

- Pre-save validation via SFM's own compiler + local checks: missing labels, conflicting sides,
  invalid slots, always-false conditions, exclusion covering all resources, timer below server minimum, etc.
- **Issues panel**: click to locate (camera jump + breathing outline), most issues have one-click fix
- **Cost badge**: per-card execution cost estimate (probes/second) on the top-right corner

### Resource Tag Filtering

- **Visual tag picker**: pick an item → see its tags (localized name, source, coverage hint, member grid)
  or search the full library; pinyin search for Chinese names
- **And / Or / Not**: free-form boolean grouping with per-pill delete
- **NBT component filter** (both sides installed): pick non-default components from items —
  enchantments (with level), potion type, custom name, custom_data (nested paths / numeric comparison);
  compiles to `with #nbt:minecraft/enchantments/sharpness` pseudo-tags intercepted by the server mixin

### JEI Integration

- JEI ingredient list stays visible while editing; the editor auto-narrows
- All resource slots are JEI drop targets: drag items (left-click), fluid containers (right-click); bookmarks work too
- Fully usable without JEI (built-in catalog with pinyin search)

### Performance

- Pure client-side — **zero tick handlers registered**, no server overhead
- Incremental layout engine: only re-lays out cards whose content hash changed
- Hit-test object pooling, NBT picker registry cached once per session

### Server TPS Tools (optional, all off by default)

Mod list → select this mod → **Config** (in-game GUI, or edit `config/sfmfactorystudio-common.toml`):

- **Idle backoff**: idle managers stretch their timer interval; resume on first successful transfer
- **Per-tick global budget**: cap total manager time per tick; overflow deferred to next tick
- **Defaults = vanilla SFM behaviour** (throughput and first-item latency unchanged);
  changes take effect immediately, no restart needed

## Installation

### 1.21.1 (NeoForge)

1. Install [Super Factory Manager 4.34.0](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager)
2. Drop `SFM-Factory-Studio-1.21.1-0.9.0.jar` into `mods/`

### 1.20.1 (Forge)

1. Install [Super Factory Manager 4.34.0](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager)
2. Drop `SFM-Factory-Studio-1.20.1-0.9.0.jar` into `mods/`

| Setup | Editor | NBT tag filter | TPS tools |
|---|---|---|---|
| Client only | ✅ | hidden (entry auto-hides) | ❌ |
| Client + Server | ✅ | ✅ unlocked (`with #nbt:…`) | ✅ opt-in |

### Getting started

1. Place a **Factory Manager**, insert a **disk**
2. Open the manager GUI → click **Factory Studio** on the left
3. Build your program from the block palette → click **Save**

## Building

Requires JDK 21:

```bash
./gradlew build        # main: bundled 1.21.1 JAR in build/libs/; 1.20.1 branch: release JAR in build/release/
./gradlew test         # editor, localization and model regression tests
```

Release JARs bundle [PinIn](https://github.com/Towdium/PinIn) (MIT) for pinyin search.
Place SFM 4.34.0's jar at `libs/sfm-4.34.0.jar` before building (see `gradle.properties`).

## License & Credits

- This mod: [Mozilla Public License 2.0](LICENSE)
- Based on [SFM-GUI](https://github.com/MimosaLW/SuperFactoryManager-GUI) (MPL-2.0, by Mimosa_LW / TeamDman)
- [Super Factory Manager](https://github.com/TeamDman/SuperFactoryManager) (MPL-2.0, by TeamDman)
- Bundles [PinIn](https://github.com/Towdium/PinIn) (MIT, by Towdium) pinyin search library
