package com.gtnh.sculptor.brush;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * A box of terrain recorded from a saved world, read from {@code sculptor-test-terrain.txt}. A solid block reads as
 * stone and any other block as air.
 *
 * <p>
 * Hand-built test terrain lacks the shapes that break the brushes, such as an undercut, a ledge overhead or a crevice
 * narrower than the brush. A brush reads block identity only through {@code Vegetation.isFoliage()}. That method uses
 * the block registry, and a unit test has no registry. So the file has one bit per block: solid or not.
 * </p>
 *
 * <pre>
 *     noBottomSmoothing 873 6 -657 69 65 AAAAA/8AAAAAAAAAP/wAAAA...
 * </pre>
 *
 * <p>
 * A line has the name, the minimum X, Y and Z, the X and Z span, the Y span, and one bit per block in base64, with X
 * fastest, then Z, then Y.
 * </p>
 */
public final class RecordedTerrain extends NullWorld {

    private static final String RESOURCE = "/sculptor-test-terrain.txt";
    private static final int FIELDS = 7;

    private final int minX, minY, minZ, span, ySpan;
    private final byte[] bits;

    private RecordedTerrain(String[] fields) {
        this.minX = Integer.parseInt(fields[1]);
        this.minY = Integer.parseInt(fields[2]);
        this.minZ = Integer.parseInt(fields[3]);
        this.span = Integer.parseInt(fields[4]);
        this.ySpan = Integer.parseInt(fields[5]);
        this.bits = Base64.getDecoder()
            .decode(fields[6]);
    }

    public static RecordedTerrain named(String name) {
        try (InputStream in = RecordedTerrain.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing test resource " + RESOURCE);

            final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                final String[] fields = line.trim()
                    .split(" ");
                if (fields.length == FIELDS && fields[0].equals(name)) return new RecordedTerrain(fields);
            }
            throw new IllegalStateException("no recorded terrain called " + name + " in " + RESOURCE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public boolean isSolid(int x, int y, int z) {
        final int dx = x - minX, dy = y - minY, dz = z - minZ;
        if (dx < 0 || dz < 0 || dy < 0 || dx >= span || dz >= span || dy >= ySpan) return false;

        final long index = ((long) dy * span + dz) * span + dx;
        return (bits[(int) (index >> 3)] & (0x80 >> (int) (index & 7))) != 0;
    }

    @Override
    public BaseBlock getLazyBlock(Vector position) {
        return getBlock(position);
    }

    @Override
    public BaseBlock getBlock(Vector position) {
        return new BaseBlock(
            isSolid(position.getBlockX(), position.getBlockY(), position.getBlockZ()) ? BlockID.STONE : BlockID.AIR);
    }
}
