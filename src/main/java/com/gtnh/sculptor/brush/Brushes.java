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

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * Brush implementations, all written against {@link SculptorEditContext}.
 *
 * <p>
 * These are Sculptor's own rather than WorldEdit's {@code Brush} classes, because WorldEdit's
 * read the world through {@code EditSession} methods that bypass its extent chain. That is fine
 * when applying for real and wrong when previewing. Reading through the context instead means
 * every brush here previews exactly as it commits, including the ones that read back positions
 * they just wrote.
 * </p>
 *
 * <p>
 * WorldEdit's own {@code /brush} commands are untouched and remain available to the player.
 * </p>
 */
public final class Brushes {

    private static final BaseBlock AIR = new BaseBlock(BlockID.AIR);

    private Brushes() {}

    // ---------------------------------------------------------------- shared helpers

    /** Smooth taper from 1 at the brush centre to 0 at its edge. */
    private static double falloff(double distance, double radius) {
        if (distance >= radius) return 0.0D;
        double t = 1.0D - (distance / radius);
        // Smoothstep: flat in the middle, easing to nothing at the rim, so repeated strokes
        // accumulate into rounded forms instead of cones.
        return t * t * (3.0D - 2.0D * t);
    }

    /**
     * The block a surface-shaping brush should build with: the chosen material if the user asked
     * for that, otherwise whatever the column is already made of, so raised ground keeps its
     * own material.
     */
    private static BaseBlock surfaceMaterial(SculptorEditContext ctx, BrushParams params, int x, int surfaceY, int z) {
        if (params.useSelectedMaterial) return params.material;
        return ctx.getBlock(new Vector(x, surfaceY, z));
    }

    // ---------------------------------------------------------------- shapes

    public static void sphere(SculptorEditContext ctx, Vector centre, BrushParams params, boolean hollow)
        throws MaxChangedBlocksException {
        int r = params.radius;
        double rSq = (r + 0.5D) * (r + 0.5D);
        double innerSq = (r - 0.5D) * (r - 0.5D);

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dSq = dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dSq > rSq) continue;
                    if (hollow && dSq < innerSq) continue;

                    int y = centre.getBlockY() + dy;
                    if (y < 0 || y > 255) continue;
                    ctx.setBlock(centre.getBlockX() + dx, y, centre.getBlockZ() + dz, params.material);
                }
            }
        }
    }

    public static void cylinder(SculptorEditContext ctx, Vector centre, BrushParams params, boolean hollow)
        throws MaxChangedBlocksException {
        int r = params.radius;
        int height = Math.max(1, params.secondary);
        double rSq = (r + 0.5D) * (r + 0.5D);
        double innerSq = (r - 0.5D) * (r - 0.5D);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dSq = dx * dx + (double) dz * dz;
                if (dSq > rSq) continue;
                if (hollow && dSq < innerSq) continue;

                for (int dy = 0; dy < height; dy++) {
                    int y = centre.getBlockY() + dy;
                    if (y < 0 || y > 255) continue;
                    ctx.setBlock(centre.getBlockX() + dx, y, centre.getBlockZ() + dz, params.material);
                }
            }
        }
    }

    // ---------------------------------------------------------------- terrain

    /**
     * Raise or lower the surface, tapering toward the edge of the brush.
     */
    public static void raiseLower(SculptorEditContext ctx, Vector centre, BrushParams params, int sign)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;

        // How far a fully-weighted column moves. Tied to radius so a bigger brush makes a
        // proportionally bigger hill rather than a wide flat step.
        double maxLift = Math.max(1.0D, r * params.strength);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                int amount = (int) Math.round(maxLift * weight);
                if (amount <= 0) continue;

                int x = cx + dx;
                int z = cz + dz;
                int surface = ctx.surfaceY(x, z, cy + r + 1, cy - r - 1);
                if (surface == Integer.MIN_VALUE) continue;

                if (sign > 0) {
                    BaseBlock fill = surfaceMaterial(ctx, params, x, surface, z);
                    for (int i = 1; i <= amount; i++) {
                        int y = surface + i;
                        if (y > 255) break;
                        ctx.setBlock(x, y, z, fill);
                    }
                } else {
                    for (int i = 0; i < amount; i++) {
                        int y = surface - i;
                        if (y < 0) break;
                        ctx.setAir(x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Pull the surface toward the height of the block the brush is centred on.
     */
    public static void flatten(SculptorEditContext ctx, Vector centre, BrushParams params)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int targetY = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                int x = cx + dx;
                int z = cz + dz;
                int surface = ctx.surfaceY(x, z, targetY + r + 1, targetY - r - 1);
                if (surface == Integer.MIN_VALUE) continue;

                // Move only part of the way, scaled by falloff, so the brush edge blends
                // instead of leaving a cliff.
                int desired = (int) Math.round(surface + (targetY - surface) * weight * params.strength);
                moveColumnTo(ctx, params, x, z, surface, desired);
            }
        }
    }

    /**
     * Average each column's height against its neighbours.
     *
     * <p>
     * Heights are sampled into an array first and the whole grid is then rewritten, so columns
     * are smoothed against the original surface rather than against half-smoothed neighbours --
     * otherwise the result would depend on iteration order and drift in the scan direction.
     * </p>
     */
    public static void smooth(SculptorEditContext ctx, Vector centre, BrushParams params, int iterations)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;
        int width = 2 * r + 1;

        int[] heights = new int[width * width];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                heights[(dz + r) * width + (dx + r)] = ctx.surfaceY(cx + dx, cz + dz, cy + r + 1, cy - r - 1);
            }
        }

        int[] source = heights;
        for (int pass = 0; pass < Math.max(1, iterations); pass++) {
            int[] next = new int[width * width];
            for (int iz = 0; iz < width; iz++) {
                for (int ix = 0; ix < width; ix++) {
                    next[iz * width + ix] = averageNeighbours(source, width, ix, iz);
                }
            }
            source = next;
        }

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                int index = (dz + r) * width + (dx + r);
                int original = heights[index];
                int smoothed = source[index];
                if (original == Integer.MIN_VALUE || smoothed == Integer.MIN_VALUE) continue;

                int desired = (int) Math.round(original + (smoothed - original) * weight * params.strength);
                moveColumnTo(ctx, params, cx + dx, cz + dz, original, desired);
            }
        }
    }

    /** Mean of the 3x3 neighbourhood, skipping empty columns so they do not drag the average. */
    private static int averageNeighbours(int[] heights, int width, int ix, int iz) {
        int sum = 0;
        int count = 0;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                int nx = ix + ox;
                int nz = iz + oz;
                if (nx < 0 || nz < 0 || nx >= width || nz >= width) continue;
                int h = heights[nz * width + nx];
                if (h == Integer.MIN_VALUE) continue;
                sum += h;
                count++;
            }
        }
        return (count == 0) ? Integer.MIN_VALUE : Math.round((float) sum / count);
    }

    /** Raise or cut a single column from its current surface to a target height. */
    private static void moveColumnTo(SculptorEditContext ctx, BrushParams params, int x, int z, int surface,
        int desired) throws MaxChangedBlocksException {
        if (desired == surface) return;

        if (desired < surface) {
            for (int y = surface; y > desired; y--) {
                if (y < 0) break;
                ctx.setAir(x, y, z);
            }
        } else {
            BaseBlock fill = surfaceMaterial(ctx, params, x, surface, z);
            for (int y = surface + 1; y <= desired; y++) {
                if (y > 255) break;
                ctx.setBlock(x, y, z, fill);
            }
        }
    }

    /**
     * Strip barely-attached blocks, or fill nearly-enclosed gaps.
     *
     * <p>
     * Erode and melt are the same neighbour count read in opposite directions, so they share an
     * implementation.
     * </p>
     */
    public static void erode(SculptorEditContext ctx, Vector centre, BrushParams params, boolean melt)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;

        // Strength picks how aggressive the cut is: at full strength a block needs almost all of
        // its neighbours to survive.
        int threshold = melt ? (int) Math.round(2 + 3 * (1.0D - params.strength))
            : (int) Math.round(2 + 3 * params.strength);

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (falloff(Math.sqrt(dx * dx + (double) dy * dy + (double) dz * dz), r) <= 0.0D) continue;

                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (y < 0 || y > 255) continue;

                    boolean solid = !ctx.isAir(x, y, z);
                    if (melt == solid) continue;

                    int neighbours = countSolidNeighbours(ctx, x, y, z);

                    if (!melt && solid && neighbours < threshold) {
                        ctx.setAir(x, y, z);
                    } else if (melt && !solid && neighbours >= threshold) {
                        BaseBlock fill = params.useSelectedMaterial ? params.material
                            : firstSolidNeighbour(ctx, x, y, z);
                        if (fill != null) ctx.setBlock(x, y, z, fill);
                    }
                }
            }
        }
    }

    private static int countSolidNeighbours(SculptorEditContext ctx, int x, int y, int z) {
        int count = 0;
        if (!ctx.isAir(x + 1, y, z)) count++;
        if (!ctx.isAir(x - 1, y, z)) count++;
        if (y < 255 && !ctx.isAir(x, y + 1, z)) count++;
        if (y > 0 && !ctx.isAir(x, y - 1, z)) count++;
        if (!ctx.isAir(x, y, z + 1)) count++;
        if (!ctx.isAir(x, y, z - 1)) count++;
        return count;
    }

    /** Fill with whatever the surroundings are made of, so melting keeps the local material. */
    private static BaseBlock firstSolidNeighbour(SculptorEditContext ctx, int x, int y, int z) {
        int[][] offsets = { { 0, -1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 } };
        for (int[] offset : offsets) {
            int ny = y + offset[1];
            if (ny < 0 || ny > 255) continue;
            BaseBlock block = ctx.getBlock(new Vector(x + offset[0], ny, z + offset[2]));
            if (block.getType() != BlockID.AIR) return block;
        }
        return null;
    }

    /**
     * Drop unsupported blocks straight down onto the first thing beneath them.
     */
    public static void gravity(SculptorEditContext ctx, Vector centre, BrushParams params)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (falloff(Math.sqrt(dx * dx + (double) dz * dz), r) <= 0.0D) continue;

                int x = cx + dx;
                int z = cz + dz;

                // Collect the column top-down, then repack it onto the floor. Reading and
                // writing in one pass would see blocks it had already moved.
                int floor = cy - r;
                int ceiling = cy + r;

                java.util.List<BaseBlock> stack = new java.util.ArrayList<BaseBlock>();
                for (int y = floor; y <= ceiling; y++) {
                    if (y < 0 || y > 255) continue;
                    BaseBlock block = ctx.getBlock(new Vector(x, y, z));
                    if (block.getType() != BlockID.AIR) {
                        stack.add(new BaseBlock(block));
                        ctx.setAir(x, y, z);
                    }
                }

                int y = Math.max(0, floor);
                for (BaseBlock block : stack) {
                    if (y > 255) break;
                    ctx.setBlock(x, y, z, block);
                    y++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- foliage

    /**
     * Remove leaves, grass, flowers, vines and similar within the brush.
     *
     * <p>
     * Identified by vanilla {@link Material} rather than a block-id list, so modded GTNH plants
     * are caught too. WorldEdit's own block registry is no use here -- on Forge it resolves to
     * bundled vanilla-only data and returns nothing for modded blocks.
     * </p>
     */
    public static void removeFoliage(SculptorEditContext ctx, Vector centre, BrushParams params)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = params.radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (falloff(Math.sqrt(dx * dx + (double) dy * dy + (double) dz * dz), r) <= 0.0D) continue;

                    int y = cy + dy;
                    if (y < 0 || y > 255) continue;

                    Vector pos = new Vector(cx + dx, y, cz + dz);
                    if (isFoliage(ctx.getBlock(pos))) {
                        ctx.setBlock(pos, AIR);
                    }
                }
            }
        }
    }

    public static boolean isFoliage(BaseBlock block) {
        int id = block.getType();
        if (id == BlockID.AIR) return false;

        switch (id) {
            // Mushroom caps and double plants report materials that are not in the set below,
            // so name them explicitly.
            case BlockID.BROWN_MUSHROOM_CAP:
            case BlockID.RED_MUSHROOM_CAP:
            case BlockID.DOUBLE_PLANT:
                return true;
            default:
                break;
        }

        Block mcBlock = Block.getBlockById(id);
        if (mcBlock == null) return false;

        Material material = mcBlock.getMaterial();
        return material == Material.leaves || material == Material.plants || material == Material.vine;
    }

}
