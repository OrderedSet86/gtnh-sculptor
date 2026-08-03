# Changelog

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
