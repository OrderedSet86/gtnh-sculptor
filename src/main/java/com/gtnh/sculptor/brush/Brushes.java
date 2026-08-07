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
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockVine;
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

    /**
     * The top block of the actual ground, ignoring anything growing on it.
     *
     * <p>
     * Plain "highest non-air" would treat a tuft of grass as the surface, so raising a grassy
     * hillside would stack copies of the grass rather than the ground under it.
     * </p>
     */
    private static int terrainSurfaceY(SculptorEditContext ctx, int x, int z, int topY, int bottomY) {
        int from = Math.min(255, topY);
        int to = Math.max(0, bottomY);
        for (int y = from; y >= to; y--) {
            BaseBlock block = ctx.getBlock(new Vector(x, y, z));
            if (block.getType() == BlockID.AIR) continue;
            if (isFoliage(block)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
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
                int surface = terrainSurfaceY(ctx, x, z, cy + r + 1, cy - r - 1);
                if (surface == Integer.MIN_VALUE) continue;

                if (sign > 0) {
                    raiseColumn(ctx, params, x, z, surface, amount);
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
     * Extend a column upward, keeping its surface block on top.
     *
     * <p>
     * Naively copying the surface block upward turns a grassy hill into a solid block of grass.
     * Real ground has a skin over a filler, so the old surface is buried as filler and the
     * surface block is re-laid on the new top. The filler is whatever was directly beneath --
     * dirt under grass, sand under sand -- so this works for any material without a lookup
     * table, modded ones included.
     * </p>
     */
    private static void raiseColumn(SculptorEditContext ctx, BrushParams params, int x, int z, int surface, int amount)
        throws MaxChangedBlocksException {
        BaseBlock top = params.useSelectedMaterial ? params.material : ctx.getBlock(new Vector(x, surface, z));

        BaseBlock filler = top;
        if (!params.useSelectedMaterial && surface > 0) {
            BaseBlock below = ctx.getBlock(new Vector(x, surface - 1, z));
            if (below.getType() != BlockID.AIR && !isFoliage(below)) {
                filler = below;
            }
        }

        // Everything from the old surface up to just below the new top becomes filler; the old
        // surface block is now buried and should not stay a surface block.
        for (int y = surface; y < surface + amount; y++) {
            if (y > 255) break;
            ctx.setBlock(x, y, z, filler);
        }

        int newTop = Math.min(255, surface + amount);
        ctx.setBlock(x, newTop, z, top);
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
                int surface = terrainSurfaceY(ctx, x, z, targetY + r + 1, targetY - r - 1);
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

        int[] original = new int[width * width];
        double[] heights = new double[width * width];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int index = (dz + r) * width + (dx + r);
                int surface = terrainSurfaceY(ctx, cx + dx, cz + dz, cy + r + 1, cy - r - 1);
                original[index] = surface;
                heights[index] = (surface == Integer.MIN_VALUE) ? Double.NaN : surface;
            }
        }

        // Heights stay fractional between passes. Rounding each iteration back to whole blocks
        // destroys exactly the sub-block detail smoothing works with: the mean of a slope moves
        // a column by a fraction of a block, which rounds straight back to where it started, and
        // the whole operation becomes a no-op.
        double[] source = heights;
        for (int pass = 0; pass < Math.max(1, iterations); pass++) {
            double[] next = new double[width * width];
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
                int surface = original[index];
                double smoothed = source[index];
                if (surface == Integer.MIN_VALUE || Double.isNaN(smoothed)) continue;

                // Blend by strength alone. Falloff bounds the area -- the loop above skips
                // anything past the rim -- but must not scale the correction as well: the
                // features worth smoothing sit near the edge of a wide smoothing radius, where
                // falloff is smallest, so damping by it shrinks a genuine one-block correction
                // to a fraction and rounding then discards it entirely.
                int desired = (int) Math.round(surface + (smoothed - surface) * params.strength);
                moveColumnTo(ctx, params, cx + dx, cz + dz, surface, desired);
            }
        }
    }

    /** Mean of the 3x3 neighbourhood, skipping empty columns so they do not drag the average. */
    private static double averageNeighbours(double[] heights, int width, int ix, int iz) {
        double sum = 0.0D;
        int count = 0;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                int nx = ix + ox;
                int nz = iz + oz;
                if (nx < 0 || nz < 0 || nx >= width || nz >= width) continue;
                double h = heights[nz * width + nx];
                if (Double.isNaN(h)) continue;
                sum += h;
                count++;
            }
        }
        return (count == 0) ? Double.NaN : sum / count;
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

    /**
     * Whether a block counts as foliage: leaves, grass, flowers, saplings, vines, mushrooms.
     *
     * <p>
     * Checked by block class first and material second. Modded packs routinely define their own
     * {@code Material} instances for custom leaves and plants, and materials are compared by
     * identity, so a material-only test quietly misses most of GTNH's flora. The block classes
     * are what mods actually extend.
     * </p>
     */
    public static boolean isFoliage(BaseBlock block) {
        int id = block.getType();
        if (id == BlockID.AIR) return false;

        switch (id) {
            // Mushroom caps and double plants do not report a foliage material.
            case BlockID.BROWN_MUSHROOM_CAP:
            case BlockID.RED_MUSHROOM_CAP:
            case BlockID.DOUBLE_PLANT:
                return true;
            default:
                break;
        }

        Block mcBlock = Block.getBlockById(id);
        if (mcBlock == null) return false;

        if (mcBlock instanceof BlockLeavesBase || mcBlock instanceof BlockBush || mcBlock instanceof BlockVine) {
            return true;
        }

        Material material = mcBlock.getMaterial();
        return material == Material.leaves || material == Material.plants || material == Material.vine;
    }

}
