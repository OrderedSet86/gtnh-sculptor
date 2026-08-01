# Sculptor

An interactive creative world editor for Minecraft 1.7.10 (GT New Horizons).

WorldEdit already wins on edit *operations*. What it lacks is *interaction*: you type
a command and hope. Sculptor adds the layer that modern creative-building tools have
and 1.7.10 does not —

- **Brushes with live preview** — see the result as a translucent ghost before you commit.
- **An in-game editor panel** — tool palette, radius/strength/falloff sliders, block picker.
- **A cinematic camera** — free camera decoupled from the player, with keyframed paths.

It is built on a vendored fork of [worldedit-gtnh](https://github.com/GTNewHorizons/worldedit-gtnh),
which supplies the edit engine. See [NOTICE](NOTICE) for provenance and licensing.

## How the preview works

`EditSession` builds a chain of `Extent` decorators whose root is the thing that
actually writes blocks. Sculptor adds a preview mode that swaps that root for a
`PreviewExtent`, which captures changes instead of applying them. Every other stage of
the pipeline is untouched, so **every existing brush and shape operation gains a
preview for free** — they never learn they are not writing to the world.

The captured `Map<BlockVector, BaseBlock>` is then three things at once: the input to
the ghost renderer, the record for undo, and the diff that would go over the wire for
multiplayer.

## Status

Early. Working so far:

- [x] Preview capture (`PreviewExtent`, `EditSession` preview mode)
- [x] Ghost renderer (`PreviewWorld`, `GhostRenderer`)
- [ ] Brush interaction loop
- [ ] Editor panel
- [ ] Camera
- [ ] Falloff/strength terrain brushes

## Building

```sh
./gradlew build
```

Requires a JDK matching `gradle/gradle-daemon-jvm.properties`. Tests run headlessly and
need no Minecraft client.

## License

LGPL-3.0, as required by the vendored WorldEdit. See [LICENSE](LICENSE).
