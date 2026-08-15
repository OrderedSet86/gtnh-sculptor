# Changelog

## 0.4.0

### Performance

- **The ghost preview is much cheaper to rebuild.** Dragging a large brush across terrain
  re-runs the brush and re-bakes its geometry up to twenty times a second, and each rebuild
  was allocating hundreds of thousands of short-lived objects — enough to show as a frame
  rate drop while the crosshair moved. Three things changed. The pending changes are
  unpacked into an array over their bounding box for the bake, so the thirteen to twenty
  questions `RenderBlocks` asks about each block are array reads rather than allocating a
  key and probing a map of tens of thousands of entries. Blocks buried inside the brush are
  skipped, since they emit nothing visible but used to pay full price to discover that —
  about four blocks in five of a sphere. And the default placement mask no longer reads the
  block it is about to overwrite, an answer it discards.

  Standing still costs the same as before: this is the rebuild, not the draw.

### Fixes

- **A sphere or cylinder placed on a slope now sits on it, with auto-smooth on.** Both fill
  upward from the height you aimed at, across their whole footprint, so on a slope the
  downhill part had nothing underneath and hung in the air — two hundred columns of it over
  an eleven-block gap, in the case this was measured on. Smoothing could never fix that: it
  is a height map, it moves the top of a column and never looks below it. The shape is now
  extended down onto the ground first.

  It follows the drop however deep it is. What decides whether a shape is settled is
  whether any part of it is resting on the terrain, not how far the ground happens to be
  below — so a shape dug into a cliff edge follows that cliff all the way down, and one
  floating in clear sky, touching nothing, is left exactly as it was drawn.

  **There are two ways to want this, so there is a button for it.** "Fill to ground" is the
  above: the shape becomes part of the hill, and its top tapers down into the terrain around
  it. "Cantilever" leaves the overhang hanging and sweeps its underside down to meet the
  slope instead, closing the void behind a free edge that keeps its full height — a ledge
  rather than a plateau. That mode also leaves the face you drew exactly as you drew it and
  blends only the ground around it, since a shape kept for its shape should not then have
  that shape worked over.

  The cantilever takes its support from whatever the shape touches, not only from what is
  underneath it. An arch is built by spanning out from something standing *beside* the new
  piece, and while support meant "the ground below" a span like that found none at all and
  was left as the flat slab it was drawn as — the higher the span, the more certainly. It
  also curves down by about the shape's own height and no further: chasing whatever happens
  to be below turns a soffit into a curtain wall, which is what the other mode is for. A
  piece placed touching nothing at all is still left exactly as drawn.

  Cantilever mode no longer deletes terrain that was already there. It keeps the shape you
  drew, so cutting the landscape around it to meet that shape is the wrong half of the
  bargain: on a rough cliff face it was trimming every bump within reach by a block or two,
  over a hundred blocks for a single stroke, scattered and nowhere near the shape. It blends
  by adding now. Filling to ground is unchanged and still reshapes freely — there the shape
  is meant to become part of the hill.

  **A shape cut into a rock face now gets an underside at all.** The pass worked out where
  the shape was by looking at the world after placing it, which only reads correctly where
  the shape hangs in clear air — merged into a cliff, under a ledge or down a crevice, it
  measured the cliff instead and either used the wrong thickness or skipped the column as
  part of the terrain. Measured on a real cliff face, a cylinder came out with a droop of
  `0 4 0 0 0 0 0 0 0 0 0 0 0`: nothing, across the whole disc. It asks the brush where it
  put its blocks now, so being buried in rock changes nothing.

  That also settles the stalactite under an overhanging ledge — a ten-block cylinder
  beneath a thirty-block ledge used to hang a thirty-one block spike, because the ledge's
  height was being read as the shape's.

  Support counts when it is near enough for the underside to curve down to it, rather than
  only when it comes right up to the shape's base — a shape placed at a cliff edge usually
  stands a block or two proud of the top it springs from, and requiring an exact meeting
  switched the whole pass off for the commonest placement there is.

  With the auto-smooth slider at zero nothing changes: a cylinder is exactly a cylinder,
  hanging base and all, which is what building in the air needs. Hollow shapes are never
  filled, since that would stop them being hollow.
- **Auto-smooth tapers what you placed instead of reshaping what you placed it on.** The
  pass moved every column to its local mean, and beside a nineteen-block cylinder that mean
  is nineteen blocks away — so one stroke at full auto-smooth hollowed out the slope it was
  dropped into and raised pillars out of the ground below it.

  The two directions are now bounded differently, because they are not the same act. Shaving
  the stroke's own new material back down is the taper and is not bounded at all: a cylinder
  standing proud of a hillside follows the slope all the way to ground level, however far
  that is. Cutting *into* ground that was already there, or raising a column above where it
  stood, is limited to a third of the brush radius. On a radius-12 cylinder over a rough
  slope that is a sixteen-block taper with at most four blocks of the hillside disturbed;
  it used to be sixteen blocks of hillside dug out and fourteen blocks of pillar.

  The smooth brush itself is untouched and stays unbounded — you asked for that one.
- **Removal markers no longer shimmer against the terrain they mark.** The red boxes sat
  just inside their block and relied on a polygon offset to pull them in front, which buys a
  distance-dependent amount of depth — too little up close, far too much at range, and a
  pixel-by-pixel coin toss in between. They enclose the block now.
- **"Air only" now binds the auto-smooth pass.** The placement mask governs building rather
  than carving, so that choosing "air only" does not silently disable every brush that
  removes blocks. Auto-smooth is the exception: it is a pass you did not ask for, and it
  lowers columns, so it was eating the ground around whatever had just been placed — the
  one thing that setting exists to prevent. It may still cut back material the same stroke
  placed, which was never protected ground.
- **Smoothing no longer strands material in mid-air.** A ledge with nothing under it still
  reads as a surface to the height map, so smoothing cleared the gap beneath it and stamped
  a fresh surface block into thin air, one per column — a floating shelf. There is no
  connected surface there to smooth, so those columns are now left exactly as they are.
  This is what produced floating islands when a sphere was dropped into a steep hillside
  with auto-smooth turned up.
- **Foliage removal strips modded tree trunks.** It matched `BlockLog`, and Biomes O' Plenty
  logs extend `Block` directly, so every BOP trunk read as ordinary terrain and the brush
  walked past it. Trunks are now identified by the `logWood` ore dictionary entry as well,
  which is the thing mods actually agree on, with Forge's `isWood` as a further fallback.


## 0.3.1

**No changes to the mod.** The jar behaves exactly as 0.3.0 does; there is no reason to
update if you already have it.

Release notes are now assembled from this file instead of from a here-doc buried in the
release workflow, and a release whose version has no section here fails rather than
publishing an empty body. This release exists to exercise that.


## 0.3.0

**Breaking: the undo commands are now `/scu` and `/scr`.** `//sc-undo` and `/sc-undo` no
longer exist. Undo is reached for constantly while sculpting and did not deserve nine
keystrokes and a dash. `//scu` and `//scr` work too, for the double-slash habit.

### Editing

- **Camera paths can be saved and loaded.** `/scpath save <name>`, `load`, `list`,
  `delete`, and Save/Load buttons in the panel's camera column. Paths are NBT files under
  `config/sculptor/paths/`, so a shot survives a restart, survives a stray press of `Del`,
  and can be copied between instances or handed to someone else.
- **Trees plant on any ground.** Vanilla's generators only grow on grass, dirt or
  farmland and refuse silently otherwise — so a tree brushed onto sculpted stained clay or
  stone looked like the brush had simply done nothing. The soil is now reported as dirt
  for the generator's benefit only: no block is written, the material you sculpted with is
  still there afterwards, and nothing extra lands in undo history.

### Fixes

- **Aggressive smoothing hollowed out steep ground.** Surface finding searched a window of
  `centre ± (radius + 1)`, so on relief taller than that it began its scan *inside* the
  hill and reported a buried block as the surface. Lowering then cleared from there down
  and left everything above untouched — an intact slab of terrain over a fresh cavity.
  Columns are now probed outward from their own height, with no window at all.
- **Raise at a large radius lifted scattered single columns.** The same window, in the
  other direction: ground more than `radius + 1` below the crosshair reported no surface
  and was skipped, so on a slope whichever columns happened to poke above the boundary
  rose and their neighbours did not. There was never any randomness in the brush.
- **Lowering left plants floating.** Foliage sits above the surface, so it was outside the
  range being cleared and survived the ground dropping out from under it.


## 0.2.0

**Breaking: Sculptor is now an addon to WorldEdit and requires it.**

Through 0.1.0 Sculptor vendored the whole of WorldEdit under its original package
names, which made it a replacement rather than a companion — two jars exporting
`com.sk89q.worldedit` into Forge's flat classloader collide, and no config flag fixes
that. It also broke workflows even as the only mod, because moving the config directory
moved the schematics directory with it.

It now depends on `worldedit-gtnh` and ships only its own code. **Keep WorldEdit
installed** — do not remove it as 0.1.0's instructions said. WorldEdit keeps all its own
commands and workflows; Sculptor sits beside them.

### Editing

- **Separate undo.** Brush commits no longer land on WorldEdit's undo stack. `//undo`
  will not revert a brush stroke, and `//sc-undo` / `//sc-redo` will not revert a `//`
  command.
- **Three-way placement mask** — overwrite anything, air only, or replace the selected
  material only.
- **Auto-smooth** after placement and terrain brushes, reaching past the brush so the new
  shape blends into surrounding ground.
- **Use-selected-material toggle** for the brushes that otherwise preserve existing terrain.
- **Tree brush** — plants the biome's own species, previewable and undoable. Vanilla tree
  generation normally bypasses `EditSession` entirely, so WorldEdit's own trees escape
  `//undo`; Sculptor's do not.
- **Foliage removal** now strips tree trunks along with leaves and plants.

### Fixes

- **Free camera targeted the wrong block.** The view rendered 1.62 blocks above where the
  targeting ray began, so edits landed below the crosshair.
- **Night vision was lost in free camera** — fog, sky and shader lighting all read the
  camera entity, which carried no potion effects.
- **Keybinds reset on restart.** The colon in descriptions like "Sculptor: commit brush"
  broke `GameSettings` parsing, so rebinds were discarded and then overwritten. Bindings
  are lang keys now; existing custom binds will not migrate.
- **Terrain brushes destroyed the surface layer.** Raising stacked grass; lowering exposed
  bare dirt. All column reshaping now carries the surface block to the new top with filler
  beneath, for vanilla and modded terrain alike.
- **Preview z-fighting** against real blocks.
- **Smoothing was close to a no-op** — heights were rounded between passes, and the
  correction was damped exactly where blending was needed.
- **Preview lied for height-map brushes in 0.1.0.** `EditSession.getBlockType` bypassed the
  capture, so smooth, flatten, raise and erode sampled unmodified terrain. The new
  `SculptorEditContext` owns both sides of the read, so this cannot recur.


## 0.1.0

First release.

### Editing

- **Live brush preview.** Every brush previews as a translucent ghost before it is committed.
  Blocks being placed are drawn with their real models and culled against existing terrain;
  blocks being removed are drawn as red boxes, so a brush that only deletes still shows you
  what it will do.
- **Eleven brushes.** Sphere, hollow sphere, cylinder, hollow cylinder, smooth and gravity come
  from WorldEdit unmodified. Raise, lower, flatten, erode and melt are new, and apply an amount
  that tapers toward the edge of the stroke rather than stamping a shape.
- **Sculpt mode** with continuous preview at the crosshair, scroll-wheel radius, 160-block
  reach, and a commit key that repeats while held for effects built up over several passes.
- **Editor panel** for brush, radius, second parameter, strength, material and camera speeds.

### Camera

- **Free camera** decoupled from the player, at an editable speed.
- **Keyframed paths** interpolated with a Catmull-Rom spline. Keyframes are recorded at one of
  three speed tiers, and a segment takes as long as its length needs at that speed rather than
  a fixed time, so long and short moves read as the same camera.

### Multiplayer

- Commits are applied server-side through the player's WorldEdit session, so `//undo` works on
  a Sculptor edit.
- Brush radius, second parameter and edit distance are bounded server-side, and applying a
  brush requires permission — op by default, or `sculptor.brush` with a permissions mod.

### Notes

Sculptor vendors WorldEdit and registers the same commands, so it **replaces** WorldEdit
rather than accompanying it. Installing both will collide.

Known gaps are listed in the README.
