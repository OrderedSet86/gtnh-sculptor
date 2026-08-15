# Handoff

Working notes for whoever picks this up next. Read this before touching brush or preview code —
several things here look like free choices and are not.

Current state: **0.4.0 in progress** on `main`, 47 tests green.

---

## What this is

An interactive creative world editor for Minecraft 1.7.10 (GT New Horizons): brushes with a live
translucent preview, an editor panel, a free camera with keyframed paths.

**Sculptor is an addon to WorldEdit, not a fork of it.** It depends on `worldedit-gtnh` for the
edit engine and registers no `//` commands beyond its own `/scu`, `/scr` and `/scpath`. Through
0.1.0 it vendored WorldEdit's whole source tree under the original package names; that made it a
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
  SculptorMod.java          @Mod entry point; server commands, and the Forge config dir
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
    CameraPathStore         The only file IO in the mod: config/sculptor/paths/*.dat
  render/GhostRenderer      Bakes pending changes to a VBO, draws on RenderWorldLastEvent
  preview/PreviewWorld      IBlockAccess over pending changes, for RenderBlocks
  world/SculptorGenWorld    net.minecraft.world.World routing into the context (trees only)
  network/SculptorNetwork   Commit packet; server applies and records history
  command/SculptorCommand   /scu, /scr  (server)
  command/SculptorPathCommand  /scpath  (client -- a path never reaches the server)
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

**The placement mask must only gate placements, not removals** — with one exception. Gating
removals too means "air only" silently disables every carving brush, since none can write air over
a block that is by definition not air. The exception is the auto-smooth pass, which the player did
not choose and which lowers columns: under "air only" it was cutting into untouched ground, which
is the one thing that setting exists to prevent. `SculptorBrush.apply` turns on
`ctx.setGuardRemovals` for the duration of that pass only, and `ctx.trackWrites` from the start of
the stroke so undoing the stroke's own new material is still allowed. Tracking is opt-in because
the set of written positions grows with the brush volume, which at the server's maximum radius is
millions of entries.

**Smoothing is a height map and cannot see underneath anything.** It moves the top of a column.
A cylinder aimed at a slope fills upward across its whole disc, so its downhill half hangs over a
gap — and no amount of smoothing touches that gap, because nothing in a height map is about the
underside. `Brushes.settleOntoGround` extends the solid shapes down onto the ground before the
smoothing pass runs, gated on the auto-smooth slider so a plain cylinder stays a true cylinder.

There are two reasonable answers, and `OverhangMode` picks between them. `settleOntoGround` fills
to the terrain; `cantileverOntoGround` leaves it hanging and sweeps the underside down to meet the
slope, so the void behind the free edge is closed and the free edge keeps its full height.

Three ways to get that wrong, all of which were built before the right one:

1. **A box blur.** The obvious tool, and it does nothing. A shape's base is flush with the ground
   it springs from, so the underside and the ground sit at the same height everywhere; the field
   is uniform and a mean has nothing to lean on.
2. **A fixed span out from the anchor.** Reaches the shape's flat base a few columns in and leaves
   everything past it exactly as it was — a slab over a void with a sheer edge, which is the
   floating island the whole pass exists to remove. The curve has to be normalised to each
   column's own position between anchor and edge (`toRim / (toAnchor + toRim)`) so it spans the
   overhang however wide that is.
3. **Sweeping it the other way**, thinning the free edge to nothing. That is the same curve upside
   down and gives a wedge tapering away, not a ledge.

Both distances come from `spreadDistances`, a breadth-first walk over the footprint.

**Support is not only what is underneath.** An arch is built by spanning out from something that
stands *beside* the new piece. While "anchored" meant "this column's material reaches the ground
below", a horizontal span found no anchor anywhere and the whole pass bailed, leaving the flat slab
it was drawn as — and no amount of tuning the curve mattered, because it never ran.
`touchesSolidBeside` reads the pre-stroke heights of the four neighbouring columns, which is why
`groundBefore` is sampled over the wider `reach` disc: the columns just outside the footprint have
to be available to ask about.

The tolerance on that test is the fiddly part, and both ends of it are wrong:

- Demanding the neighbour reach the underside **exactly** switches the whole pass off over a
  difference nobody can see. A shape placed at a cliff edge normally stands a block or two proud of
  the top it springs from, so this reads as "no support" for the commonest placement there is, and
  a span looks completely untouched.
- Accepting support at **any** depth anchors a shape to ground ninety blocks below and drags the
  underside down to it.

So it is bounded by the same distance the sweep itself is allowed — the shape's own height. Support
near enough for the curve to reach counts; support further away does not.

**The cantilever takes the shape's extent from the brush, not from the world.** `shapeProfile`
answers, per column, where this brush put its own material: a cylinder's base and height, a
sphere's chord. Working it out afterwards -- find the top of the column, walk down to the bottom of
the solid run -- reads correctly only where the shape hangs in clear air. Placed into a cliff,
under a ledge or down a crevice it merges with what is already there, and the run is then the
cliff's: the pass measured the wrong thickness, or dismissed the column as "fused to terrain" and
shaped nothing at all. A cylinder cut into a real rock face came out with a droop of
`0 4 0 0 0 0 0 0 0 0 0 0 0` across the entire disc.

Taking it from the brush deleted three special cases that existed only to paper over the
misreading: the fused-to-terrain test, the shape-top guard, and the per-column thickness clamp.
Resist reintroducing anything that infers the shape from the result.

**The height probe can walk underneath an overhang without seeing it.** `terrainSurfaceY` climbs
only when it starts inside ground; otherwise it descends. `sampleHeights` carries a running probe
along each row, so where the probe is in open air it drops to the valley floor and keeps going —
past a ledge overhead, which it never looks up at. A column fused to that ledge therefore reports
the valley as its ground, reads as free-hanging material as tall as the ledge, and the cantilever
hangs a spike the height of it. Ten-block cylinder under a thirty-block ledge, thirty-one block
spike.

Two things guard it. A column whose solid run rises above anything the brush could have placed is
treated as anchored, not as overhang — that is what `shapeTop` is for. And the sweep depth comes
from the brush's declared height rather than from the run, since a shape fused to something taller
would otherwise take the taller thing's size as its own. The second is redundant for cylinders once
the first is in place (a non-fused cylinder column's run *is* its height), so do not be surprised
when mutating it alone changes nothing; it is not redundant for spheres, whose run varies per
column, and that case has no test.

The sweep is also bounded to about the shape's own height. Following the ground however far away it
is turns a soffit into a curtain wall — eighty blocks, measured, for a ten-block cylinder spanning
off a tower. Filling to ground is the mode for that.

The cantilever also passes `keepPlacement` to the smoothing pass, which then skips every column
the stroke built in. Without it the height map works the top over as well, pulling each column
toward whatever the terrain beside it happens to be doing — up against the hill, down over the drop
— and carving the flat face into an island of lumps. Filling to ground must **not** set it: there
the taper down into the surrounding terrain is the entire point.

Also: a column joined to the hillside has no underside. Walking its solid run down goes through the
terrain to bedrock. Reading that as the shape's base made the first version strip a hundred and
twenty blocks of hillside out from under itself.

What decides whether a shape is settled is whether **any** of its footprint is resting on terrain,
not how far below the ground is. A distance limit stood in for that at first, at twice the radius,
and it is the kind of stand-in that looks fine in a test and fails in the world: the same hillside
that settled correctly with a small brush went back to floating with a large one, the threshold
moving with the radius — 24 blocks, then 32, then 40. If you find yourself reaching for a distance
here, you are approximating "is it standing on something", so test that instead.

The trap when testing this is the metric. "Is the block under the surface air" says no gap for a
nineteen-block slab hanging in space, because eighteen of those blocks are its own. Walk the whole
solid run down and check what is under *that*. A test using the weak version passed while two
hundred columns hung in the air, and the fix it was guarding was reported as working.

**Auto-smooth is bounded by *what* it cuts, not by *how far*.** Smoothing moves a column to the
local mean, and next to a nineteen-block cylinder the local mean is nineteen blocks away — so the
pass meant to soften a seam hollowed out the hillside and raised pillars out of the valley. The
obvious fix, a symmetric cap on movement, is wrong in the other direction: it leaves the placement
standing as a flat-topped mesa, because tapering a tall shape back down to ground level is a big
movement and a legitimate one.

So the two directions have different bounds. Shaving away the stroke's own new material is
unbounded. Cutting below the ground that was there before the stroke, or raising a column above
it, is capped at `maxStep`. That needs the pre-stroke heights, which is why `SculptorBrush.apply`
calls `sampleHeights` *before* the switch — afterwards there is no way to tell new material from
old ground. It is one int per column of the smoothing disc, a few thousand entries, not the
per-block write set that `trackWrites` keeps.

Two traps when measuring this. Compare against the same stroke with auto-smooth at **zero**, not
against the untouched world — the latter mostly measures the placement, and once reported a
26-block "rise" that was the cylinder. And do not bin columns as inside or outside the brush by
`radius`: the shapes use `(radius + 0.5)²`, so the edge ring lands in the wrong bucket and the
taper reads as damage to untouched ground.

**A surface is not the same thing as ground.** The height map cannot tell a two-block ledge over a
drop from a hillside — both are the top of a column. Lowering one clears the gap beneath it and
stamps a fresh surface block into thin air, one per column, which is a floating shelf; that is
where the floating islands under a heavily auto-smoothed sphere came from. `Brushes.smooth` walks
the target down to the first thing that can hold it up, and leaves the column alone if there is
nothing. Refusing the cut outright was the first attempt, and it is what blocked the taper above —
under the overhanging half of a shape there is always a gap, and that is exactly the case that has
to reach the ground. Deliberately only in `smooth`: a player who picks the lower brush and aims at
a floating platform means it.

**Modded logs do not extend `BlockLog`.** Biomes O' Plenty's `BlockBOPLog` extends `Block`
directly, so `instanceof BlockLog` missed every BOP trunk and the removal brush walked past it.
Matching the wood `Material` instead is worse — it catches planks, and takes out anything built
from them. `Brushes.isLog` asks the `logWood` ore dictionary entry, which Forge registers for
vanilla and BOP registers for its own, and memoises the answer per block id because the removal
brush asks about every block in a sphere.

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

**Never bound the surface search to a window around the brush centre.** Through 0.2.1
`terrainSurfaceY` scanned `centreY ± (radius + 1)`, which is a tempting thing to write because it
looks like a cheap bound on an otherwise unbounded scan. It fails silently in both directions on
terrain with real relief. Above the window, the downward scan begins *inside* the hill and returns
a buried block as the surface — `reshapeColumn`'s lowering half then carves from there and leaves
everything above it standing, which is how smoothing a steep face produced an intact roof over a
cavity. Below it, the column reports no surface and its brush skips it, which is how raise at a
large radius lifted a scatter of single columns instead of a dome. Both read as randomness; there
is none in those brushes. Columns are probed outward from a *neighbour's* height now
(`sampleHeights`), which is what keeps an unbounded scan cheap — terrain is continuous, so the
probe lands on or beside the answer. Climbing is contiguous so a bridge overhead is not mistaken
for ground. The corollary is that a height-map brush used inside a cave finds the mountain above
it: correct, and still not what you want. They are surface tools.

**Vanilla tree generators only grow on grass, dirt and farmland, and refuse without saying so.**
Every generator a biome can hand out asks the block below its base
`canSustainPlant(world, x, y-1, z, UP, Blocks.sapling)`, and Forge's default answers no for
everything else; `generate()` then returns `false` with no exception, so the brush reports success
and nothing appears. `SculptorGenWorld` answers that read with dirt over a 2×2 footprint
(`WorldGenHugeTrees` checks all four base columns) and drops writes there, since
`WorldGenMegaPineTree` stamps podzol over whatever satisfied the check. Do not "fix" this by
swapping the block for real and swapping it back: that lands in the change set twice, inflates the
"applied N blocks" count, and is rejected outright by an air-only or replace-selected placement
mask — silently reinstating the bug.

**The bake is the expensive part of previewing, not the draw.** Rebuilding happens when the
crosshair moves to a new block or a setting changes — up to 20 times a second while dragging —
and it runs inline inside `RenderWorldLastEvent`, so its whole cost lands in one frame. The draw
itself is two VBO binds. If someone reports the preview costing frames, the first question is
whether it persists while standing still: if it does, the culprit is the draw state
(`GL_CULL_FACE` off, blending on, and unbounded overdraw in x-ray mode) and nothing in this
paragraph applies.

`PreviewWorld` unpacks the change set into an array over its bounding box for exactly this reason.
`RenderBlocks` asks 13-20 questions per block — seven brightness lookups, six
`shouldSideBeRendered`, the bounds and the colour — and each one used to allocate a `BlockVector`
and probe a map with a virtual-call-heavy `equals`. Half a million allocations per bake, twenty
times a second. Keep lookups on that path allocation-free. The map is still there as a fallback
for a change set too spread out to unpack.

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
in-game verification — say so rather than implying it is covered. `Brushes.isLog` is entirely
uncovered for the same reason: block lookup and the ore dictionary both need a running game. The
one exception worth knowing:
`isFoliage` matches mushroom caps and `DOUBLE_PLANT` by block id *before* it consults the registry,
so those three are the only plants a headless test can use as plants — anything else reads as
solid terrain and proves nothing.

**Some cases only exist in a real world.** `RecordedTerrain` replays a box of blocks lifted out of
a saved world, kept as `src/test/resources/sculptor-test-terrain.txt` — solid-or-air only, which is
all the brushes read headlessly. Two reported bugs were reproduced there after hand-built terrain
failed to provoke either: the shapes that break these brushes are undercuts, ledges overhead and
faces too rough to think of modelling, and a test world someone builds by hand is a model of what
they already believe. To add a case, extract another box around the brush target; the coordinates
come from the F3 position and look angles, raytraced the way `SculptorBrushTool` does.

**Test terrain has to have relief.** Every brush test ran on flat ground until 0.3.0, which is
exactly why the surface-window bug above survived the suite: on flat ground the window is never
wrong. `CliffWorld` in `BrushBehaviourTest` is thirty blocks of step for that reason. A height-map
brush tested on a plane is testing almost nothing.

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

**Write the changelog section before releasing.** The release body is assembled from
`.github/release-preamble.md` — which holds the install prose and a `<!-- changelog -->` marker —
and the `## <version>` section of `CHANGELOG.md`. A version with no section fails the workflow,
deliberately, and it fails *before* the tag is pushed so there is nothing to clean up. Edit those
two files rather than the workflow: the install notes claimed Sculptor replaced WorldEdit for two
releases after it stopped doing so, because they were buried in YAML and edited by hand on the
release page each time.

**Releases are manual.** Actions → Release → type the version. The workflow creates the tag
itself, builds, verifies the jar declares `modid=sculptor` and the right version, and publishes.
It is deliberately *not* triggered by pushing a tag, since it makes the tag — both triggers would
race. On failure it deletes the tag so the version can be retried.

**Dependency notes.** Biomes O' Plenty is in the dev client only, and nothing compiles against it
— its logs are reached through the ore dictionary, not by type. It is there because the tree and
foliage-removal brushes cannot be unit-tested at all and BOP is what they get wrong. CurseForge
serves the obfuscated release jar, so it needs `rfg.deobf(...)` around the coordinate; without that
it loads and then dies on `NoSuchMethodError: func_71410_x`, its SRG names having no counterpart in
a dev client's MCP-named Minecraft.

`worldedit-gtnh` is only on JitPack, scoped to that one module in `repositories.gradle`. `jchronic`/`jlibnoise` are pinned to EngineHub with `exclusiveContent`
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
- **Raise stands on top of tree trunks.** `isFoliage` deliberately excludes `BlockLog`, so a log is
  ground for surface finding — a raise brush should not tunnel through a tree. Over a forest that
  reads as stray blocks at canopy height. Deliberate, and arguably the wrong call; revisit if it
  bites.
- **Raise terraces.** A column moves by a whole number of blocks, so a ten-block dome has ten steps.
  Inherent to voxels, not a falloff bug.
- **Camera path speeds are not saved.** The tiers live in `CameraSettings` and reset to their
  defaults each launch; a saved path stores which *tier* each keyframe uses, not its rate. So a
  reloaded path paces correctly against whatever the tiers mean today, and a customised tier is
  gone at the next restart.
- **Preview at long range can be stale** where chunks are not loaded client-side. The brush reaches
  160 blocks; render distance is often less.
- **The ghost bake runs on the render thread.** Off-thread is the obvious next optimisation, but
  mod `ISBRH` implementations are not thread-safe — it needs `TessellatorManager.isOnMainThread()`
  and `AngelicaBlockSafetyRegistry` gating.
- **The brush run still allocates per block.** Roughly nine objects for every `setBlock`, mostly
  `Vector` and `BlockVector` on the way through `SculptorEditContext` and `PreviewBuffer`, three
  of them the same coordinate converted three times. The bake was the larger half and has been
  dealt with; this half has not. Fixing it means touching the map's key type, which is shared with
  the commit path, so measure before assuming it is worth the risk.
- **The ghost draws with backface culling off** and blending on, with depth writes disabled and
  optionally no depth test at all. That is at least double the fragment work over screen coverage
  that grows with the square of the radius. Never measured; it is the thing to look at if the
  preview costs frames while standing still.
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
