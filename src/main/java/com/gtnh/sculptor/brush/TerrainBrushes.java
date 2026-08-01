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

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.function.pattern.Pattern;

/**
 * Terrain brushes with falloff and strength.
 *
 * <p>
 * This is the part WorldEdit does not have. Its brushes are binary -- a block is either set or
 * it is not -- which is why terrain shaping there means repeatedly running commands and
 * checking the result. These brushes instead apply an amount that tapers from the centre of the
 * brush to its edge, so a stroke blends into the surrounding ground rather than stamping a
 * shape onto it.
 * </p>
 *
 * <p>
 * They read the world back as they work: raising a column has to know where its surface is, and
 * eroding has to count solid neighbours. Against a preview session those reads see the pending
 * changes rather than the untouched world, so what the ghost shows is what committing produces.
 * That property is the whole reason the capture buffer is read-your-writes.
 * </p>
 */
public final class TerrainBrushes {

    private TerrainBrushes() {}

    /** Smooth taper from 1 at the brush centre to 0 at its edge. */
    private static double falloff(double distance, double radius) {
        if (distance >= radius) return 0.0D;
        double t = 1.0D - (distance / radius);
        // Smoothstep: flat in the middle, easing to nothing at the rim, so repeated strokes
        // accumulate into rounded forms instead of cones.
        return t * t * (3.0D - 2.0D * t);
    }

    private static boolean isAir(EditSession session, int x, int y, int z) {
        return session.getBlock(new Vector(x, y, z))
            .getType() == BlockID.AIR;
    }

    /**
     * Find the highest non-air block in a column, searching down from {@code topY}.
     *
     * @return the surface Y, or {@link Integer#MIN_VALUE} if the column is empty
     */
    private static int surfaceY(EditSession session, int x, int z, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            if (y < 0 || y > 255) continue;
            if (!isAir(session, x, y, z)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Raise or lower the surface, tapering toward the edge of the brush.
     */
    public static final class RaiseLower implements Brush {

        private final int sign;
        private final double strength;

        public RaiseLower(int sign, double strength) {
            this.sign = sign;
            this.strength = strength;
        }

        @Override
        public void build(EditSession session, Vector position, Pattern pattern, double size)
            throws MaxChangedBlocksException {
            int cx = position.getBlockX();
            int cy = position.getBlockY();
            int cz = position.getBlockZ();
            int radius = (int) Math.ceil(size);

            // How far a fully-weighted column moves. Tied to radius so a bigger brush makes a
            // proportionally bigger hill rather than a wide flat step.
            double maxLift = Math.max(1.0D, size * strength);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + (double) dz * dz);
                    double weight = falloff(distance, size);
                    if (weight <= 0.0D) continue;

                    int amount = (int) Math.round(maxLift * weight);
                    if (amount <= 0) continue;

                    int x = cx + dx;
                    int z = cz + dz;
                    int surface = surfaceY(session, x, z, cy + radius + 1, cy - radius - 1);
                    if (surface == Integer.MIN_VALUE) continue;

                    if (sign > 0) {
                        // Extend the column upward using the block already at its surface, so
                        // raised ground keeps the material it was made of.
                        BaseBlock material = session.getBlock(new Vector(x, surface, z));
                        for (int i = 1; i <= amount; i++) {
                            int y = surface + i;
                            if (y > 255) break;
                            session.setBlock(new Vector(x, y, z), material);
                        }
                    } else {
                        for (int i = 0; i < amount; i++) {
                            int y = surface - i;
                            if (y < 0) break;
                            session.setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
                        }
                    }
                }
            }
        }
    }

    /**
     * Pull the surface toward the height of the block the brush is centred on.
     */
    public static final class Flatten implements Brush {

        private final double strength;

        public Flatten(double strength) {
            this.strength = strength;
        }

        @Override
        public void build(EditSession session, Vector position, Pattern pattern, double size)
            throws MaxChangedBlocksException {
            int cx = position.getBlockX();
            int targetY = position.getBlockY();
            int cz = position.getBlockZ();
            int radius = (int) Math.ceil(size);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + (double) dz * dz);
                    double weight = falloff(distance, size);
                    if (weight <= 0.0D) continue;

                    int x = cx + dx;
                    int z = cz + dz;
                    int surface = surfaceY(session, x, z, targetY + radius + 1, targetY - radius - 1);
                    if (surface == Integer.MIN_VALUE) continue;

                    // Move only part of the way, scaled by falloff, so the edge of the brush
                    // blends instead of leaving a cliff.
                    int desired = (int) Math.round(surface + (targetY - surface) * weight * strength);
                    if (desired == surface) continue;

                    if (desired < surface) {
                        for (int y = surface; y > desired; y--) {
                            if (y < 0) break;
                            session.setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
                        }
                    } else {
                        BaseBlock material = session.getBlock(new Vector(x, surface, z));
                        for (int y = surface + 1; y <= desired; y++) {
                            if (y > 255) break;
                            session.setBlock(new Vector(x, y, z), material);
                        }
                    }
                }
            }
        }
    }

    /**
     * Remove blocks that are barely attached, and optionally fill ones that are nearly enclosed.
     *
     * <p>
     * Erode strips protruding detail; melt does the inverse and fills crevices. Both are the
     * same neighbour count read in opposite directions, so they share an implementation.
     * </p>
     */
    public static final class Erode implements Brush {

        private final boolean melt;
        private final double strength;

        public Erode(boolean melt, double strength) {
            this.melt = melt;
            this.strength = strength;
        }

        @Override
        public void build(EditSession session, Vector position, Pattern pattern, double size)
            throws MaxChangedBlocksException {
            int cx = position.getBlockX();
            int cy = position.getBlockY();
            int cz = position.getBlockZ();
            int radius = (int) Math.ceil(size);

            // Strength picks how aggressive the cut is: at full strength a block needs almost
            // all its neighbours to survive.
            int threshold = melt ? (int) Math.round(2 + 3 * (1.0D - strength)) : (int) Math.round(2 + 3 * strength);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        double distance = Math.sqrt(dx * dx + (double) dy * dy + (double) dz * dz);
                        if (falloff(distance, size) <= 0.0D) continue;

                        int x = cx + dx;
                        int y = cy + dy;
                        int z = cz + dz;
                        if (y < 0 || y > 255) continue;

                        boolean solid = !isAir(session, x, y, z);
                        if (melt == solid) continue;

                        int solidNeighbours = countSolidNeighbours(session, x, y, z);

                        if (!melt && solid && solidNeighbours < threshold) {
                            session.setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
                        } else if (melt && !solid && solidNeighbours >= threshold) {
                            BaseBlock material = firstSolidNeighbour(session, x, y, z);
                            if (material != null) {
                                session.setBlock(new Vector(x, y, z), material);
                            }
                        }
                    }
                }
            }
        }

        private static int countSolidNeighbours(EditSession session, int x, int y, int z) {
            int count = 0;
            if (!isAir(session, x + 1, y, z)) count++;
            if (!isAir(session, x - 1, y, z)) count++;
            if (y < 255 && !isAir(session, x, y + 1, z)) count++;
            if (y > 0 && !isAir(session, x, y - 1, z)) count++;
            if (!isAir(session, x, y, z + 1)) count++;
            if (!isAir(session, x, y, z - 1)) count++;
            return count;
        }

        /** Fill with whatever the surroundings are made of, so melting keeps the local material. */
        private static BaseBlock firstSolidNeighbour(EditSession session, int x, int y, int z) {
            int[][] offsets = { { 0, -1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 } };
            for (int[] offset : offsets) {
                int ny = y + offset[1];
                if (ny < 0 || ny > 255) continue;
                BaseBlock block = session.getBlock(new Vector(x + offset[0], ny, z + offset[2]));
                if (block.getType() != BlockID.AIR) return block;
            }
            return null;
        }
    }

}
