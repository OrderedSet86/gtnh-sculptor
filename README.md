# Sculptor

An interactive creative world editor for Minecraft 1.7.10 (GT New Horizons).

WorldEdit already wins on edit *operations*. What it lacks is *interaction*: you type a
command and hope. Sculptor adds the layer that modern creative-building tools have and
1.7.10 does not —

- **Brushes with live preview** — see the result as a translucent ghost before you commit.
- **Terrain brushes with falloff** — raise, lower, flatten, erode, melt, tapering from the
  centre of the stroke so it blends into surrounding ground instead of stamping a shape.
- **An in-game editor panel** — brush picker, radius/strength sliders, material picker.
- **A cinematic camera** — free camera decoupled from the player, with keyframed paths.

## Installing

> **Sculptor replaces WorldEdit. Do not install both.**
> It vendors WorldEdit and registers the same commands, so running them together will
> collide. Remove `worldedit-*.jar` from `mods/` first.

Drop the jar in `mods/`. It is a universal Forge mod — the same file goes on the client
and on the server, and both need it for the editor to work in multiplayer.

Requires **GTNHLib**, **ModularUI2**, and **UniMixins**. **Angelica** is optional but is
what the ghost renderer is designed against.

On a server, applying a brush requires permission. With no permissions mod installed that
resolves to being **op** (or creative, if WorldEdit's `use-in-creative` is enabled); with
one, the node is `sculptor.brush`.

## Controls

Everything is rebindable under **Options → Controls → Sculptor**.

| Key | Action |
|---|---|
| `B` | Toggle sculpt mode |
| `V` | Commit the previewed brush (hold to repeat) |
| Scroll | Brush radius |
| `N` | Open the editor panel |
| `C` | Toggle free camera |
| `J` / `K` / `M` | Add camera keyframe — slow / normal / fast approach |
| `P` | Play or stop the camera path |
| `Del` | Clear the camera path |

A keyframe's tier sets the speed of the move *into* it, and a segment takes as long as its
length needs at that speed — so a long establishing move and a short detail move read as
the same camera. Speeds are editable, in blocks/sec, in the panel.

## How the preview works

`EditSession` builds a chain of `Extent` decorators whose root is the thing that actually
writes blocks. Sculptor adds a preview mode that swaps that root for a `PreviewExtent`,
which captures changes instead of applying them. Every other stage of the pipeline is
untouched, so **every brush and shape operation gains a preview for free** — they never
learn they are not writing to the world.

The capture is read-your-writes, which matters more than it sounds: brushes like smooth,
gravity and erode read back positions they have just written, and would otherwise preview
differently from how they commit.

Committing goes through the server even in singleplayer, because the integrated server owns
the blocks. The client sends which brush it used and where; the server replays it against a
real `EditSession` bound to the player's session, which is what makes `//undo` work.

## Building

```sh
./gradlew build
```

Requires a JDK matching `gradle/gradle-daemon-jvm.properties`. Tests run headlessly and need
no Minecraft client.

To test multiplayer locally:

```sh
./gradlew runServer
./gradlew runClient -PconnectTo=localhost
```

## Status

Working: preview capture, ghost renderer, brush loop, editor panel, camera paths, terrain
brushes, multiplayer commit with permission checks.

Known gaps:

- The editor panel is a modal screen rather than a docked, non-modal one.
- Erode and melt are `O(radius³)`; large radii may be slow.
- Preview at long range can be stale where chunks are not loaded client-side.
- Blueprints are not implemented.

## License

LGPL-3.0, as required by the vendored WorldEdit. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
