# Changelog

## 0.5.0

**Update the client and the server together.** A brush stroke between 0.4.0 and 0.5.0 fails or
runs with wrong settings.

### Added

- **`/sclayers` fills the WorldEdit selection in bands of altitude.** With
  `/sclayers 10:stone *:grass 10:bedrock -s 3`, the top 10 blocks are stone, the bottom 10 are
  bedrock, and grass fills the blocks between. Air stays air. `-s N` moves the band edges up and
  down by up to N blocks, in waves. `//undo` reverts the fill, and `/scu` does not.
- **The panel has a Layers tab for the same fill**, with up to 5 layers.
- **"Replace only this block" has a separate block to replace**, in a row under the mode button.
  The brush changes only that block, for a carving brush too: an air brush with dirt to replace
  removes only dirt.
- **You can take air as the material from an empty hand**, to carve.
- **You can use the hotbar keys while the panel is open.**

### Changed

- **The editor panel has three tabs: Brush, Layers and Camera**, and fits on the screen at
  1920×1080 with the automatic GUI scale.
- **You pick the brush from a menu.**
- **A setting that does nothing for the current brush is hidden.** The Iterations slider of
  Smooth goes from 1 to 8.
- **The material setting has clearer names**: *New blocks: match terrain* and *New blocks: brush
  material*.
- **Each block row has the block icon and the item name**, for example Pink Wool.
- **The HUD is in the top-left corner, on a dark background**, clear of the chat.
- **The free camera starts 4 blocks behind and 2 blocks above you**, not inside the head of your
  player model.
- **The Strength and Auto-smooth sliders have tick marks**, in steps of 5%.
- **You can translate Sculptor with a resource pack.**

### Fixes

- **Carving with auto-smooth on is the same in both overhang modes.** In cantilever mode, the
  carved area was not smoothed. In fill-to-ground mode, the preview had extra red boxes.


## 0.4.0

Most of this release is about auto-smooth. It did several things it must not do.

### Added

- **A shape placed on a slope no longer hangs in the air, and you choose what happens
  instead.** A sphere or a cylinder fills upward from the height you aim at, across its whole
  footprint. On a slope, the downhill part of the shape had nothing below it. One measured
  case left two hundred columns above a gap of eleven blocks. Smoothing cannot correct this,
  because it is a height map. It moves the top of a column and never looks below one.

  If auto-smooth is on, the new **Overhang** button in the panel selects one of two results.
  *Fill to ground* extends the shape down onto the terrain. The shape becomes part of the
  hill, and its top tapers into the ground around it. *Cantilever* keeps the overhang in the
  air, and curves its underside down to meet the slope. This closes the space behind an outer
  edge that keeps its full height. Cantilever also keeps the face you drew, and blends the
  ground around the shape only by addition.

  Both modes take support from anything the shape touches. This includes a wall beside the
  shape, not only ground below it, so an arch that spans out from its leg works correctly.
  Neither mode changes ground far away from the shape. If the slider is at zero, nothing
  happens at all. A cylinder is then exactly a cylinder, with a base in the air, which is what
  you need to build above the ground. Sculptor never fills a hollow shape, because that makes
  it solid.

### Changed

- **The editor panel is taller**, to make space for the Overhang button.

### Performance

- **The ghost preview costs much less to rebuild.** If you drag a large brush, Sculptor runs
  the brush again and rebuilds its geometry up to twenty times each second. Each rebuild
  allocated hundreds of thousands of short-lived objects. This was enough to lower the frame
  rate while the crosshair moved.

  Three things changed. Sculptor now unpacks the pending changes into an array for the
  rebuild, instead of a map that it must search. It skips blocks inside the brush, because
  they show nothing. In a sphere, about four blocks in five are inside. Also, the default
  placement mask no longer reads the block that it replaces, because it discards that answer.

  The cost to draw the ghost is the same as before. This change is to the rebuild.

### Fixes

- **Auto-smooth now tapers what you place, instead of changing what you place it on.** It
  moved each column to the local mean height. Beside a cylinder of nineteen blocks, that mean
  is nineteen blocks away. One stroke at full strength thus made a hollow in the slope below
  the shape, and raised pillars out of the ground.

  Sculptor can cut back the new material of the stroke without a limit. This is the taper, and
  it lets a shape that stands above a hillside follow the slope down to the ground. A cut into
  ground that was already there has a limit of one third of the brush radius. The same limit
  applies to a rise above the original height. On a cylinder of radius 12 above a rough slope,
  the taper is sixteen blocks, and at most four blocks of the hillside change. Before this
  release, the same stroke cut sixteen blocks out of the hillside and raised pillars of
  fourteen blocks.

  The smooth brush does not change. It still has no limit.
- **"Air only" now also controls the auto-smooth pass.** The placement mask controls what you
  build over, not what you remove. If it controlled removal, it would stop every brush that
  cuts. Auto-smooth is the exception, because you do not ask for it and it lowers columns. It
  cut the ground around a new shape, which is what this setting exists to prevent. Auto-smooth
  can still cut back material from the same stroke.
- **Smoothing no longer leaves material in the air.** A ledge with nothing below it is still a
  surface to the height map. Smoothing thus cleared the space below the ledge, and put a new
  surface block in the air, one for each column. This made the floating islands that appeared
  when you put a sphere into a steep hillside with auto-smooth at a high value.
- **Foliage removal now removes modded tree trunks.** It matched `BlockLog`, but Biomes O'
  Plenty logs extend `Block` directly. Each BOP trunk thus looked like usual terrain, and the
  brush did not remove it. Sculptor now finds trunks with the `logWood` ore dictionary entry,
  which mods agree on, and uses `isWood` from Forge as a second test.
- **The removal markers no longer flicker against the terrain.** The red boxes were inside
  their block, and a polygon offset moved them forward. That offset gives a different depth at
  a different distance. It gave too little near the camera and too much far away. Between
  those distances, the two surfaces changed places pixel by pixel. Each box now encloses its
  block.


## 0.3.1

**The mod does not change.** The jar behaves exactly as 0.3.0 does. If you already have
0.3.0, you do not need this release.

Sculptor now builds the release notes from this file. Before, they came from text inside
the release workflow. A release fails if this file has no section for its version, instead
of publishing an empty body. This release exists to test that.


## 0.3.0

**Breaking: the undo commands are now `/scu` and `/scr`.** `//sc-undo` and `/sc-undo` no
longer exist. You use undo constantly while you sculpt, and it did not need nine keystrokes
and a dash. `//scu` and `//scr` also work, if you prefer the double slash.

### Editing

- **You can save and load camera paths.** Use `/scpath save <name>`, `load`, `list` and
  `delete`. The camera column of the panel also has Save and Load buttons. Sculptor writes
  each path to `config/sculptor/paths/` as an NBT file. A shot thus survives a restart and an
  accidental press of `Del`, and you can copy it between instances or give it to someone.
- **Trees plant on any ground.** The generators in Minecraft grow trees only on grass, dirt
  or farmland, and refuse without a message. A tree on sculpted stained clay or stone thus
  looked like the brush did nothing. Sculptor now reports the ground as dirt to the generator
  only. It writes no block, the material you sculpted with stays, and the undo history gets
  no extra entry.

### Fixes

- **Strong smoothing made a hollow in steep ground.** To find a surface, Sculptor searched a
  window of `centre ± (radius + 1)`. Where the ground was taller than that window, the search
  started inside the hill and reported a buried block as the surface. Smoothing then cleared
  the column below that block and left everything above it. The result was a solid slab of
  terrain above a new cavity. Sculptor now probes outward from each column's own height, with
  no window.
- **Raise with a large radius lifted single columns.** This is the same window in the other
  direction. Ground more than `radius + 1` below the crosshair reported no surface, and the
  brush skipped it. On a slope, only the columns that reached above that boundary rose. The
  brush contains no randomness.
- **Lower left plants in the air.** Foliage sits above the surface, thus outside the range
  that the brush cleared. It stayed when the ground below it went away.


## 0.2.0

**Breaking: Sculptor is now an addon to WorldEdit, and needs it.**

Through 0.1.0, Sculptor contained a copy of all of WorldEdit under the original package
names. This made Sculptor a replacement instead of a companion. Two jars that export
`com.sk89q.worldedit` into the flat classloader of Forge collide, and no config option
corrects this. It also broke work even as the only mod, because a change to the config
directory moved the schematics directory with it.

Sculptor now depends on `worldedit-gtnh` and contains only its own code. **Keep WorldEdit
installed.** Do not remove it, as the instructions for 0.1.0 told you to. WorldEdit keeps all
of its commands, and Sculptor operates beside them.

### Editing

- **Separate undo.** A brush commit no longer goes onto the undo stack of WorldEdit.
  `//undo` does not revert a brush stroke, and `//sc-undo` and `//sc-redo` do not revert a
  `//` command.
- **A placement mask with three settings:** overwrite anything, air only, or replace the
  selected material only.
- **Auto-smooth** after a placement brush or a terrain brush. It reaches past the brush, so
  the new shape blends into the ground around it.
- **A toggle to build with the selected material,** for the brushes that keep the terrain
  material.
- **A tree brush.** It plants the species of the biome, and you can preview it and undo it.
  Tree generation in Minecraft usually bypasses `EditSession`, so `//undo` does not remove a
  tree from WorldEdit. It does remove one from Sculptor.
- **Foliage removal** now also removes tree trunks, with the leaves and plants.

### Fixes

- **The free camera targeted the wrong block.** The view rendered 1.62 blocks above the start
  of the targeting ray, so each edit landed below the crosshair.
- **The free camera lost night vision.** The fog, the sky and the shader lighting all read the
  camera entity, and it carried no potion effects.
- **Keybinds reset at each restart.** The colon in a description such as "Sculptor: commit
  brush" broke the parser in `GameSettings`. Minecraft discarded each rebind and then wrote
  over it. The bindings are language keys now. A custom bind from an earlier version does not
  migrate.
- **Terrain brushes destroyed the surface layer.** Raise made a stack of grass, and lower
  exposed bare dirt. Every column now carries its surface block to the new top, with filler
  below it. This works for terrain from Minecraft and from other mods.
- **The preview flickered** against real blocks.
- **Smoothing did almost nothing.** Sculptor rounded the heights between passes, and it
  reduced the correction exactly where the blend was necessary.
- **The preview was wrong for height-map brushes in 0.1.0.** `EditSession.getBlockType`
  bypassed the capture, so smooth, flatten, raise and erode read the original terrain. The new
  `SculptorEditContext` owns both sides of the read, so this cannot happen again.


## 0.1.0

First release.

### Editing

- **Live brush preview.** Each brush appears as a translucent ghost before you commit it.
  Sculptor draws the blocks to place with their real models, and culls them against the
  terrain. It draws the blocks to remove as red boxes, so a brush that only deletes still
  shows you what it does.
- **Eleven brushes.** Sphere, hollow sphere, cylinder, hollow cylinder, smooth and gravity
  come from WorldEdit without a change. Raise, lower, flatten, erode and melt are new. They
  apply an amount that tapers toward the edge of the stroke, instead of stamping a shape.
- **Sculpt mode,** with a continuous preview at the crosshair, radius on the scroll wheel, a
  reach of 160 blocks, and a commit key that repeats while you hold it. Hold it to build an
  effect over several passes.
- **An editor panel** for the brush, the radius, the second parameter, the strength, the
  material and the camera speeds.

### Camera

- **A free camera,** separate from the player, at a speed you set.
- **Keyframed paths,** interpolated with a Catmull-Rom spline. You record each keyframe at one
  of three speed tiers. A segment takes as long as its length needs at that speed, instead of
  a fixed time, so a long move and a short move read as the same camera.

### Multiplayer

- Sculptor applies each commit on the server, through the WorldEdit session of the player, so
  `//undo` works on a Sculptor edit.
- The server limits the brush radius, the second parameter and the edit distance. To apply a
  brush you need permission. This is op by default, or `sculptor.brush` with a permissions
  mod.

### Notes

Sculptor contains a copy of WorldEdit and registers the same commands. It thus **replaces**
WorldEdit instead of accompanying it. If you install both, they collide.

The README lists the known gaps.
