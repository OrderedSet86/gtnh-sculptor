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

> **Sculptor requires WorldEdit and installs alongside it.** Keep `worldedit-*.jar` where it
> is — Sculptor will not load without it, and registers no `//` commands of its own beyond
> its two undo commands.

Drop the jar in `mods/`. It is a universal Forge mod — the same file goes on the client and on
the server, and both need it for the editor to work in multiplayer.

Requires **worldedit-gtnh**, **GTNHLib** and **ModularUI2**. **Angelica** is optional but is
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

Undo is a command rather than a key, since it is destructive and a key that far-reaching is too
easy to hit mid-stroke:

| Command | |
|---|---|
| `//sc-undo` | Undo your last Sculptor edit |
| `//sc-redo` | Redo the edit you undid |

These are separate from WorldEdit's `//undo`, which walks your command history and will not
revert a brush stroke — and Sculptor's will not revert a `//` command.

A keyframe's tier sets the speed of the move *into* it, and a segment takes as long as its
length needs at that speed — so a long establishing move and a short detail move read as
the same camera. Speeds are editable, in blocks/sec, in the panel.

## How the preview works

Every brush reads and writes through a `SculptorEditContext`. In preview mode that is backed by
a capture buffer and nothing reaches the world; on commit it wraps a real `EditSession`. The
brush cannot tell which, so the ghost and the result are produced by the same execution rather
than by two code paths that have to agree.

The buffer is read-your-writes, which matters more than it sounds: smooth, gravity, erode and
the raise/lower family all read back positions they have just written, and would otherwise
preview differently from how they commit.

Committing goes through the server even in singleplayer, because the integrated server owns the
blocks. The client sends which brush it used and how it was configured; the server replays it
against a real `EditSession` and records it in Sculptor's own history.

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

## Releasing

Run the **Release** workflow from the Actions tab and type the version (e.g. `0.1.0`). It tags
the commit, builds, checks the jar declares the right mod id and version, and publishes the
release with the jar attached. There is nothing to tag by hand — the workflow creates the tag,
which is why it is not also triggered by pushing one.

If the build fails, the tag it created is removed again so the same version can be retried.

## Status

Working: preview capture, ghost renderer, brush loop, editor panel, camera paths, terrain
brushes, multiplayer commit with permission checks.

Known gaps:

- The editor panel is a modal screen rather than a docked, non-modal one.
- Only Sculptor's brushes preview. WorldEdit commands such as `//set` do not, since Sculptor no
  longer modifies `EditSession`.
- Erode and melt are `O(radius³)`; large radii may be slow.
- Preview at long range can be stale where chunks are not loaded client-side.
- Trees and blueprints are not implemented.

## License

LGPL-3.0, matching WorldEdit, which it builds on. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
