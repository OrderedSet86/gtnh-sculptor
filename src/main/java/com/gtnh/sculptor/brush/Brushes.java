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

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.world.SculptorGenWorld;
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

    /** Memo for {@link #isLog}, indexed by block id -- 1.7.10 has 4096 of them. */
    private static final byte UNKNOWN = 0;
    private static final byte IS_LOG = 1;
    private static final byte NOT_LOG = 2;
    private static final byte[] LOG_CACHE = new byte[4096];

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
     * Whether a block counts as ground for surface finding: solid, and not something growing on
     * it. Plain "not air" would treat a tuft of grass as the surface, so raising a grassy hillside
     * would stack copies of the grass rather than the ground under it.
     */
    private static boolean isGround(SculptorEditContext ctx, int x, int y, int z) {
        BaseBlock block = ctx.getBlock(new Vector(x, y, z));
        return block.getType() != BlockID.AIR && !isFoliage(block);
    }

    /**
     * The top block of the actual ground in a column, probed outward from a nearby height.
     *
     * <p>
     * This deliberately takes a starting guess rather than a search window. A window anchored to
     * the brush centre -- which is what this used to be -- fails silently in both directions on
     * any terrain with real relief. Above the window, the downward scan begins <em>inside</em> the
     * hill and hands back a buried interior block as if it were the surface; the lowering half of
     * {@link #reshapeColumn} then carves from there down and leaves everything above untouched,
     * which is how smoothing a steep face produced an intact roof over a fresh cavity. Below it,
     * the column reports no surface at all and its brush skips it, so near the rim of a large
     * brush on a slope whichever columns happen to poke above the boundary get moved and their
     * neighbours do not -- raise scattering single blocks instead of lifting a dome.
     * </p>
     *
     * <p>
     * Climbing is contiguous on purpose: a column started inside a mass walks to the top of
     * <em>that</em> mass, so a bridge or platform floating in clear air overhead is not mistaken
     * for the ground.
     * </p>
     */
    private static int terrainSurfaceY(SculptorEditContext ctx, int x, int z, int nearY) {
        int y = Math.max(0, Math.min(255, nearY));

        if (isGround(ctx, x, y, z)) {
            while (y < 255 && isGround(ctx, x, y + 1, z)) y++;
            return y;
        }

        while (y >= 0) {
            if (isGround(ctx, x, y, z)) return y;
            y--;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Ground height for every column of a brush footprint, indexed {@code (dz + r) * width + (dx +
     * r)}, holding {@link Integer#MIN_VALUE} where a column has no ground at all.
     *
     * <p>
     * Sampled with a running probe rather than a fixed start, which is what keeps an unbounded
     * search cheap: terrain is continuous, so a column's neighbour is almost always within a block
     * or two and the probe lands on or beside the answer. Each row restarts from the first column
     * of the row above rather than the last, since the scan wraps back to the far side of the disc
     * at every row boundary.
     * </p>
     */
    private static int[] sampleHeights(SculptorEditContext ctx, int cx, int cy, int cz, int r) {
        int width = 2 * r + 1;
        int[] heights = new int[width * width];

        int rowStart = cy;
        for (int dz = -r; dz <= r; dz++) {
            int probe = rowStart;
            boolean rowSeeded = false;
            for (int dx = -r; dx <= r; dx++) {
                int surface = terrainSurfaceY(ctx, cx + dx, cz + dz, probe);
                heights[(dz + r) * width + (dx + r)] = surface;
                if (surface != Integer.MIN_VALUE) {
                    probe = surface;
                    if (!rowSeeded) {
                        rowStart = surface;
                        rowSeeded = true;
                    }
                }
            }
        }
        return heights;
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

        // Sampled up front, like smooth does. Each column's reshape reads and writes only its own
        // column, so this is equivalent to interleaving and keeps all three height-map brushes
        // agreeing on how the surface is found.
        int width = 2 * r + 1;
        int[] heights = sampleHeights(ctx, cx, cy, cz, r);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                int amount = (int) Math.round(maxLift * weight);
                if (amount <= 0) continue;

                int surface = heights[(dz + r) * width + (dx + r)];
                if (surface == Integer.MIN_VALUE) continue;

                reshapeColumn(ctx, params, cx + dx, cz + dz, surface, surface + sign * amount);
            }
        }
    }

    /**
     * Move a column's surface to a new height, keeping its skin on top.
     *
     * <p>
     * Terrain has a surface block over a filler -- grass over dirt, and the same shape for sand,
     * podzol, mycelium or anything a mod adds. Both directions have to preserve that. Raising by
     * copying the surface block upward produces a solid block of grass; lowering by simply
     * cutting exposes the filler and leaves bare dirt on top. Either way the result stops
     * looking like ground.
     * </p>
     *
     * <p>
     * So: the surface block is lifted to whatever the new top is, and everything below it is
     * filler taken from directly beneath the old surface. No block-id table, so it works for
     * modded terrain as well as vanilla.
     * </p>
     */
    private static void reshapeColumn(SculptorEditContext ctx, BrushParams params, int x, int z, int surface,
        int desired) throws MaxChangedBlocksException {
        if (desired == surface) return;

        int newTop = Math.max(0, Math.min(255, desired));

        BaseBlock skin = params.useSelectedMaterial ? params.material : ctx.getBlock(new Vector(x, surface, z));

        BaseBlock filler = skin;
        if (!params.useSelectedMaterial && surface > 0) {
            BaseBlock below = ctx.getBlock(new Vector(x, surface - 1, z));
            if (below.getType() != BlockID.AIR && !isFoliage(below)) {
                filler = below;
            }
        }

        if (newTop > surface) {
            // The old surface block is now buried, so it becomes filler like everything under it.
            for (int y = surface; y < newTop; y++) {
                ctx.setBlock(x, y, z, filler);
            }
        } else {
            // Foliage sits above the surface, so it is outside the cut and would be left hanging
            // once the ground under it drops. Raising does not need this -- anything growing there
            // is inside the range being filled.
            for (int y = surface + 1; y <= 255; y++) {
                BaseBlock above = ctx.getBlock(new Vector(x, y, z));
                if (above.getType() == BlockID.AIR || !isFoliage(above)) break;
                ctx.setAir(x, y, z);
            }

            for (int y = surface; y > newTop; y--) {
                ctx.setAir(x, y, z);
            }
        }

        ctx.setBlock(x, newTop, z, skin);
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

        int width = 2 * r + 1;
        int[] heights = sampleHeights(ctx, cx, targetY, cz, r);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                int surface = heights[(dz + r) * width + (dx + r)];
                if (surface == Integer.MIN_VALUE) continue;

                // Move only part of the way, scaled by falloff, so the brush edge blends
                // instead of leaving a cliff.
                int desired = (int) Math.round(surface + (targetY - surface) * weight * params.strength);
                reshapeColumn(ctx, params, cx + dx, cz + dz, surface, desired);
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

        int[] original = sampleHeights(ctx, cx, cy, cz, r);
        double[] heights = new double[width * width];
        for (int index = 0; index < original.length; index++) {
            heights[index] = (original[index] == Integer.MIN_VALUE) ? Double.NaN : original[index];
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

                // Cutting a column whose material does not reach that far down would clear the
                // gap and then stamp a surface block into thin air, one per column -- which is
                // where the floating shelves under a heavily auto-smoothed sphere came from. A
                // ledge with nothing under it has no connected surface, so there is nothing here
                // to smooth: leave the column exactly as it is.
                if (desired < surface && !isSolidDown(ctx, cx + dx, cz + dz, surface, desired)) continue;

                reshapeColumn(ctx, params, cx + dx, cz + dz, surface, desired);
            }
        }
    }

    /**
     * Whether a column is unbroken ground from {@code fromY} down to {@code toY}, both included.
     *
     * <p>
     * Costs no more than the cut it guards, since that walks the same span to clear it.
     * </p>
     */
    private static boolean isSolidDown(SculptorEditContext ctx, int x, int z, int fromY, int toY) {
        int top = Math.min(255, fromY);
        int bottom = Math.max(0, toY);
        for (int y = top; y >= bottom; y--) {
            if (!isGround(ctx, x, y, z)) return false;
        }
        return true;
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

    // ---------------------------------------------------------------- trees

    /**
     * Plant trees of whatever species the biome grows.
     *
     * <p>
     * Uses the biome's own generator ({@code BiomeGenBase.func_150567_a}), which subclasses
     * override, so modded biomes supply their own trees with no per-biome table here. Generation
     * is routed through {@link SculptorGenWorld} so it goes into the edit context rather than
     * straight into the live world -- which is what lets a tree be previewed and undone at all.
     * </p>
     *
     * <p>
     * The random seed is derived from the target position, not from a fresh {@code Random}.
     * Preview and commit are two separate executions, so an unseeded generator would grow a
     * different tree each time and the ghost would be a lie. Seeding by position also means the
     * same spot always grows the same tree, so re-previewing does not make it flicker between
     * shapes as the crosshair jitters.
     * </p>
     */
    public static void plantTrees(SculptorEditContext ctx, Vector centre, BrushParams params)
        throws MaxChangedBlocksException {
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int r = Math.max(0, params.radius - 1);

        // One tree at the smallest radius, more as it grows, thinned by strength.
        int attempts = Math.max(1, (int) Math.round((r * r) / 6.0D * params.strength));

        Random placement = new Random(seedFor(cx, cy, cz));
        SculptorGenWorld genWorld = new SculptorGenWorld(ctx);
        int skipped = 0;

        for (int i = 0; i < attempts; i++) {
            int x = (r == 0) ? cx : cx + placement.nextInt(2 * r + 1) - r;
            int z = (r == 0) ? cz : cz + placement.nextInt(2 * r + 1) - r;

            if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r) continue;

            int surface = terrainSurfaceY(ctx, x, z, cy);
            if (surface == Integer.MIN_VALUE || surface >= 254) continue;

            BiomeGenBase biome = genWorld.getBiomeGenForCoords(x, z);
            if (biome == null) continue;

            // Each tree gets its own position-derived seed, so which trees appear does not
            // depend on how many were attempted before it.
            Random treeRandom = new Random(seedFor(x, surface, z));
            WorldGenAbstractTree generator = biome.func_150567_a(treeRandom);
            if (generator == null) continue;

            // Biomes hand out *shared* generator instances, and those instances are stateful:
            // WorldGenBigTree stores the world in a field for the duration of a call, and Forge
            // nulls it again on exit to avoid leaking the world. In singleplayer the client and
            // the integrated server run on different threads in one JVM, so a preview on the
            // client and a commit on the server can be inside the same generator object at once
            // -- one finishes, nulls the field, and the other dereferences null mid-tree.
            //
            // Locking the generator serialises our two callers. It cannot cover vanilla chunk
            // population, which touches the same instances without locking, so a failed tree is
            // skipped rather than allowed to abort the whole brush.
            synchronized (generator) {
                // Vanilla only grows trees on grass, dirt or farmland. Reporting the ground as
                // dirt for the duration of the call lets one land on sculpted terrain of any
                // material, without writing anything -- see SculptorGenWorld.setSoilOverride.
                genWorld.setSoilOverride(x, surface, z);
                try {
                    generator.generate(genWorld, treeRandom, x, surface + 1, z);
                } catch (RuntimeException e) {
                    skipped++;
                } finally {
                    genWorld.clearSoilOverride();
                }
            }
        }

        if (skipped > 0) {
            // Worth surfacing rather than swallowing: a skipped tree means the ghost and the
            // committed result disagree about that tree.
            System.out.println("[Sculptor] skipped " + skipped + " tree(s) whose generator was busy");
        }
    }

    /** Stable scatter of a position into a seed. */
    private static long seedFor(int x, int y, int z) {
        long seed = x * 341873128712L + z * 132897987541L + y * 6364136223846793005L;
        seed ^= (seed >>> 33);
        return seed;
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
                    if (isVegetation(ctx.getBlock(pos))) {
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
    /**
     * Foliage plus tree trunks -- what the removal brush strips.
     *
     * <p>
     * Kept separate from {@link #isFoliage}, which decides what counts as ground. A log is not
     * ground, but it is also not something a raise brush should tunnel through, so only removal
     * treats it as vegetation.
     * </p>
     *
     * <p>
     * Matched by {@code BlockLog} rather than by wood material, which would also catch planks
     * and take out anything built from them.
     * </p>
     */
    public static boolean isVegetation(BaseBlock block) {
        return isFoliage(block) || isLog(block);
    }

    /**
     * Whether a block is a tree trunk, including modded ones.
     *
     * <p>
     * {@code instanceof BlockLog} is not enough. Biomes O' Plenty's {@code BlockBOPLog} extends
     * {@code Block} directly, so every BOP trunk read as ordinary terrain and the removal brush
     * walked straight past it. Matching on the wood {@link Material} instead would be worse: it
     * also catches planks, and would take out anything built from them.
     * </p>
     *
     * <p>
     * Three tests, cheapest first. The ore dictionary is the one mods actually agree on --
     * {@code logWood} is registered by Forge for vanilla and by BOP for its own -- and Forge's
     * {@code isWood} covers a mod that overrides it but forgot to register. That last call gets
     * a null world, which vanilla and BOP both ignore; a mod that does not is caught rather than
     * allowed to throw out of a brush stroke.
     * </p>
     */
    public static boolean isLog(BaseBlock block) {
        int id = block.getType();
        if (id <= 0 || id >= LOG_CACHE.length) return false;

        // Resolving this means an ItemStack and a set lookup, and the removal brush asks about
        // every block in a sphere, so the answer is worked out once per block type.
        byte cached = LOG_CACHE[id];
        if (cached != UNKNOWN) return cached == IS_LOG;

        boolean log = computeIsLog(id);
        LOG_CACHE[id] = log ? IS_LOG : NOT_LOG;
        return log;
    }

    private static boolean computeIsLog(int id) {
        Block mcBlock = Block.getBlockById(id);
        if (mcBlock == null) return false;
        if (mcBlock instanceof BlockLog) return true;

        Item item = Item.getItemFromBlock(mcBlock);
        if (item != null) {
            int logWood = OreDictionary.getOreID("logWood");
            for (int ore : OreDictionary.getOreIDs(new ItemStack(item, 1, 0))) {
                if (ore == logWood) return true;
            }
        }

        try {
            return mcBlock.isWood(null, 0, 0, 0);
        } catch (RuntimeException e) {
            return false;
        }
    }

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
