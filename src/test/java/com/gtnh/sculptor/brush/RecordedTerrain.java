/*
 * Sculptor, an interactive creative world editor for Minecraft 1.7.10
 * Copyright (C) Sculptor contributors
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.gtnh.sculptor.brush;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Base64;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * A box of real terrain, lifted out of a saved world.
 *
 * <p>
 * Hand-built test terrain is a model of what someone believes the world looks like, and the
 * shapes that break these brushes are exactly the ones nobody thinks to model -- an undercut, a
 * ledge overhead, a crevice narrower than the brush. Several fixes here were measured against
 * synthetic worlds that turned out not to contain the case being fixed at all.
 * </p>
 *
 * <p>
 * Only solid-or-not is recorded. That is all the brushes read: block identity reaches them
 * through {@code isFoliage}, which needs the block registry, and the registry is not bootstrapped
 * in a unit test anyway.
 * </p>
 */
public final class RecordedTerrain extends NullWorld {

    private static final String RESOURCE = "/sculptor-test-terrain.txt";

    private final int minX, minY, minZ, span, ySpan;
    private final byte[] bits;

    private RecordedTerrain(int minX, int minY, int minZ, int span, int ySpan, byte[] bits) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.span = span;
        this.ySpan = ySpan;
        this.bits = bits;
    }

    public static RecordedTerrain named(String name) {
        try (InputStream in = RecordedTerrain.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            for (String line; (line = reader.readLine()) != null;) {
                String[] parts = line.trim()
                    .split(" ");
                if (parts.length != 7 || !parts[0].equals(name)) continue;

                return new RecordedTerrain(
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]),
                    Base64.getDecoder()
                        .decode(parts[6]));
            }
            throw new IllegalStateException("no recorded terrain called " + name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the recorded box covers this column at all, so a test can stay inside it. */
    public boolean covers(int x, int z) {
        return x >= minX && z >= minZ && x < minX + span && z < minZ + span;
    }

    public boolean solid(int x, int y, int z) {
        int dx = x - minX, dy = y - minY, dz = z - minZ;
        if (dx < 0 || dz < 0 || dy < 0 || dx >= span || dz >= span || dy >= ySpan) return false;

        long index = ((long) dy * span + dz) * span + dx;
        return (bits[(int) (index >> 3)] & (0x80 >> (int) (index & 7))) != 0;
    }

    @Override
    public BaseBlock getBlock(Vector position) {
        return new BaseBlock(
            solid(position.getBlockX(), position.getBlockY(), position.getBlockZ()) ? BlockID.STONE : BlockID.AIR);
    }

    @Override
    public BaseBlock getLazyBlock(Vector position) {
        return getBlock(position);
    }
}
