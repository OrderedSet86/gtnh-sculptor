# Handoff

Working notes for whoever picks this up next. Read this before touching brush or preview code —
several things here look like free choices and are not.

Current state: **0.2.1 released**, `main` in sync with origin, clean tree, 17 tests green.

---

## What this is

An interactive creative world editor for Minecraft 1.7.10 (GT New Horizons): brushes with a live
translucent preview, an editor panel, a free camera with keyframed paths.

**Sculptor is an addon to WorldEdit, not a fork of it.** It depends on `worldedit-gtnh` for the
edit engine and registers no `//` commands beyond `//sc-undo` and `//sc-redo`. Through 0.1.0 it
vendored WorldEdit's whole source tree under the original package names; that made it a
*replacement*, since two jars exporting `com.sk89q.worldedit` into Forge's flat classloader
collide. 0.2.0 removed the vendored copy. Do not reintroduce it.

---

## The one architectural idea

**Every brush reads and writes through `SculptorEditContext`.** Nothing else. In preview mode it
is backed by a `PreviewBuffer` and nothing reaches the world; on commit it wraps a real
`EditSession`. The brush cannot tell which — so the ghost and the committed result are produced
by the same execution rather than by two code paths that have to agree.

Two properties this rests on, both load-bearing:

1. **The buffer is read-your-writes.** Smooth, gravity, erode and the raise/lower family all read
   back positions they just wrote. A buffer that answered reads from the untouched world would
   make those brushes preview differently from how they commit.
2. **Sculptor owns both sides of the read.** `EditSession.getBlock`, `getBlockType` and
   `getBlockData` all bypass WorldEdit's own extent chain and read the world directly. That is
   *why* the 0.1.0 approach (patching the extent chain) silently lied for height-map brushes, and
   why the seam had to move into a class we control.

**If you add a brush, put it in `Brushes.java` against the context and register it in the
`SculptorBrush` enum.** Do not call `EditSession` directly and do not reuse WorldEdit's `Brush`
classes — theirs read through the bypassing methods and will preview wrongly.

---

## Layout

```
com/gtnh/sculptor/
  SculptorMod.java          @Mod entry point; registers commands on server start
  proxy/                    Client and common proxies
  edit/
    SculptorEditContext     THE seam. All brush reads/writes.
    PreviewBuffer           Read-your-writes capture
    PlacementMask           overwrite / air-only / replace-selected
    SculptorHistory         Per-player undo, independent of WorldEdit's
  brush/
    SculptorBrush           Enum: metadata + dispatch + auto-smooth pass
    Brushes                 All brush implementations
    BrushParams             Immutable per-stroke config
    BrushSettings           Client-side current config; bumps a revision on change
  client/
    SculptorBrushTool       Sculpt mode, crosshair tracking, commit
    SculptorPanel           ModularUI2 editor panel
  camera/                   Free camera + keyframed paths
  render/GhostRenderer      Bakes pending changes to a VBO, draws on RenderWorldLastEvent
  preview/PreviewWorld      IBlockAccess over pending changes, for RenderBlocks
  world/SculptorGenWorld    net.minecraft.world.World routing into the context (trees only)
  network/SculptorNetwork   Commit packet; server applies and records history
  command/SculptorCommand   //sc-undo, //sc-redo
```

---

## Gotchas that cost real time

Each of these was a bug that took a while to find. They are not obvious from the code.

**Keybind descriptions must not contain a colon.** `GameSettings` persists bindings as
`key_<description>:<code>` and parses with `split(":")`, comparing only the first field. A
description like `"Sculptor: commit brush"` splits in the wrong place, matches nothing on load,
and the rebind is discarded then overwritten on the next save. Use lang keys
(`key.sculptor.*`) and add them to `en_US.lang`.

**The camera's `yOffset` must be 1.62.** `EntityRenderer.orientCamera` places the view at
`posY - (yOffset - 1.62F)`, but `rayTrace` starts from raw `posY` with no such compensation. Any
other value desyncs what you see from what you target. Also: **`posY` is already the eye anchor**
for a player in 1.7.10 — the bounding box hangs below it — so do not add `getEyeHeight()`.

**The placement mask must only gate placements, not removals.** Gating removals too means "air
only" silently disables every carving brush, since none can write air over a block that is by
definition not air.

**Biome tree generators are shared and stateful.** `BiomeGenBase.func_150567_a` returns instances
held on the biome singleton, and `WorldGenBigTree` stores the world in a field that Forge nulls on
exit. In singleplayer the client (preview) and integrated server (commit) are different threads in
one JVM, so both can be inside the same generator at once. `Brushes.plantTrees` locks the
generator, which covers Sculptor's own two callers but **not** vanilla chunk population — a tree
whose generator throws is skipped and logged.

**Anything with randomness must be seeded from position.** Preview and commit are separate
executions. An unseeded generator grows a different tree than the ghost showed, and re-previewing
makes it flicker as the crosshair jitters.

**Terrain has a skin over a filler.** Grass over dirt, sand over sand, and whatever a mod does.
Raising by copying the surface block upward gives solid grass; lowering by cutting exposes bare
dirt. `reshapeColumn` handles both directions — use it rather than writing another column mover.
There were three of these once, and the bug survived a fix because only one got repaired.

**Do not mixin Angelica's terrain renderer.** It `@Overwrite`s `RenderGlobal.sortAndRender`,
`clipRenderersByFrustum`, `markBlocksForUpdate` and `getDebugInfoRenders` at priority 900. Draw
from `RenderWorldLastEvent` instead, which is also why previewing costs zero chunk rebuilds.

---

## Testing discipline

**Mutation-test any new test.** Two tests here initially passed while the property they existed to
check was deliberately broken:

- The preview-vs-commit equivalence test passed with read-your-writes disabled, because the test
  terrain was *flat* — on flat ground no brush ever reads back a position it just wrote. The test
  world is deliberately uneven now, and carries foliage.
- It also asserts each brush **produced changes**, because an inert brush would otherwise satisfy
  the comparison by changing nothing on both sides.

**Minecraft's registries are not bootstrapped headlessly.** `Block.getBlockById` returns null, so
material-based checks and all tree generation do nothing in a unit test. `SculptorBrush.TREE` is
excluded from the equivalence loop for this reason. Anything relying on the registry needs
in-game verification — say so rather than implying it is covered.

---

## Build, run, release

```sh
./gradlew build                                    # compile + test; spotless is enforced
./gradlew spotlessApply                            # run before committing or the build fails
./gradlew runClient                                # dev client, WorldEdit pulled in automatically
./gradlew runServer                                # dedicated server, run/server/
./gradlew runClient -PconnectTo=localhost -PconnectPort=25566
```

`run/server/server.properties` is set to **25566**, not 25565 — the maintainer runs another
Minecraft server on the default port. Do not kill processes on 25565.

**Releases are manual.** Actions → Release → type the version. The workflow creates the tag
itself, builds, verifies the jar declares `modid=sculptor` and the right version, and publishes.
It is deliberately *not* triggered by pushing a tag, since it makes the tag — both triggers would
race. On failure it deletes the tag so the version can be retried.

**Dependency notes.** `worldedit-gtnh` is only on JitPack, scoped to that one module in
`repositories.gradle`. `jchronic`/`jlibnoise` are pinned to EngineHub with `exclusiveContent`
because another mirror times out from CI, and a repository that *times out* fails resolution
outright, unlike a 404 which falls through.

---

## Known gaps

- **Editor panel is modal.** Axiom's equivalent leaves the world interactive; ModularUI2 supports
  that via `OverlayManager`/`HoloUI`, but it is a bigger piece of work than the panel was.
- **Only Sculptor's brushes preview.** WorldEdit commands like `//set` do not, since Sculptor no
  longer modifies `EditSession`. Inherent to the addon model.
- **Erode and melt are O(radius³)** with six neighbour reads per block. Large radii may be slow.
  Never profiled.
- **Auto-smooth is near a no-op on raise over even ground.** Raise already tapers with a smoothstep
  falloff, so it leaves a profile whose local mean is itself. It bites on placements and uneven
  terrain. This is expected, not a bug.
- **Tree preview can disagree with the commit** if a generator is busy — see the race above.
- **Preview at long range can be stale** where chunks are not loaded client-side. The brush reaches
  160 blocks; render distance is often less.
- **The ghost bake runs on the render thread.** Off-thread is the obvious optimisation, but mod
  `ISBRH` implementations are not thread-safe — it needs `TessellatorManager.isOnMainThread()` and
  `AngelicaBlockSafetyRegistry` gating.
- **Blueprints are not implemented.**

---

## Working with the maintainer

- **Do not push.** The maintainer pushes and runs releases. Commit locally and hand over the
  commands. A push to `main` has been refused before on exactly this basis.
- **No `Co-Authored-By` trailers** in commit messages.
- **Ask before any force push**, even when a rewrite was requested.
- Feedback arrives as a numbered list, often with a screenshot. The screenshots are diagnostic —
  the terraced-hillside one pinned a bug in seconds that would have taken much longer to reason
  about. Ask for one when a report is visual.
- When a report says a feature "does nothing", check whether it is genuinely inert before assuming
  it is broken. Auto-smooth on raise really does nothing, for a good reason. Foliage removal really
  was broken, for two reasons at once.
