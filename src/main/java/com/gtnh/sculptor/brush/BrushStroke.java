package com.gtnh.sculptor.brush;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.feature.WorldGenAbstractTree;

import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.world.SculptorGenWorld;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * One run of a brush at one centre, with every read and write through a {@link SculptorEditContext}.
 *
 * <p>
 * The brushes of WorldEdit read the world through {@code EditSession} methods that go past the extent chain, and a
 * preview of those brushes is wrong. These brushes read through the context. A preview of a brush that reads a position
 * after a write to the position then matches the commit. The {@code /brush} commands of WorldEdit are unchanged.
 * </p>
 */
public final class BrushStroke {

    private static final int MAX_Y = 255;
    private static final BaseBlock AIR = new BaseBlock(BlockID.AIR);

    private final SculptorEditContext ctx;
    private final BrushParams params;
    private final int cx;
    private final int cy;
    private final int cz;
    private final int r;
    private final int width;

    public BrushStroke(SculptorEditContext ctx, Vector centre, BrushParams params) {
        this.ctx = ctx;
        this.params = params;
        this.cx = centre.getBlockX();
        this.cy = centre.getBlockY();
        this.cz = centre.getBlockZ();
        this.r = params.radius();
        this.width = 2 * r + 1;
    }

    public BrushParams getParams() {
        return params;
    }

    public void sphere(boolean hollow) throws MaxChangedBlocksException {
        final double rSq = (r + 0.5D) * (r + 0.5D);
        final double innerSq = (r - 0.5D) * (r - 0.5D);

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    final double dSq = dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dSq > rSq) continue;
                    if (hollow && dSq < innerSq) continue;

                    final int y = cy + dy;
                    if (y < 0 || y > MAX_Y) continue;
                    ctx.setBlock(new Vector(cx + dx, y, cz + dz), params.material());
                }
            }
        }
    }

    public void cylinder(boolean hollow) throws MaxChangedBlocksException {
        final int height = Math.max(1, params.secondary());
        final double rSq = (r + 0.5D) * (r + 0.5D);
        final double innerSq = (r - 0.5D) * (r - 0.5D);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double dSq = dx * dx + (double) dz * dz;
                if (dSq > rSq) continue;
                if (hollow && dSq < innerSq) continue;

                for (int dy = 0; dy < height; dy++) {
                    final int y = cy + dy;
                    if (y < 0 || y > MAX_Y) continue;
                    ctx.setBlock(new Vector(cx + dx, y, cz + dz), params.material());
                }
            }
        }
    }

    /**
     * Move the height of each column toward the mean of the 3 by 3 columns around the column, {@code iterations} times.
     *
     * <p>
     * Each pass reads the heights of the pass before, so the result does not depend on the scan order. {@code bounds}
     * limits a raise, and a cut below the ground before the stroke. A cut down to that ground is the taper of a placed
     * shape, with no limit.
     * </p>
     */
    public void smooth(int iterations, SmoothingBounds bounds) throws MaxChangedBlocksException {
        final ColumnGrid original = sampleHeights(r);
        double[] heights = new double[original.getCellCount()];
        for (int index = 0; index < heights.length; index++) {
            heights[index] = (original.getAt(index) == ColumnGrid.NONE) ? Double.NaN : original.getAt(index);
        }

        // the heights stay fractional between passes. The mean of a slope moves a column by a fraction of a block, and
        // a round to whole blocks after each pass returns the column to the start height
        for (int pass = 0; pass < Math.max(1, iterations); pass++) {
            final double[] next = new double[heights.length];
            for (int iz = 0; iz < width; iz++) {
                for (int ix = 0; ix < width; ix++) {
                    next[iz * width + ix] = averageNeighbours(heights, ix, iz);
                }
            }
            heights = next;
        }

        final int maxStep = bounds.maxStep();
        final ColumnGrid groundBefore = bounds.groundBefore();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                final int surface = original.get(dx, dz);
                final double smoothed = heights[original.indexOf(dx, dz)];
                if (surface == ColumnGrid.NONE || Double.isNaN(smoothed)) continue;

                // falloff() limits the area but does not scale the move. Features worth smoothing are near the rim of a
                // wide radius. There falloff() is small, and a scaled one-block move rounds to nothing
                int desired = (int) Math.round(surface + (smoothed - surface) * params.strength());

                int wasGround = surface;
                if (groundBefore != null) {
                    final int before = groundBefore.get(dx, dz);

                    // a column above the height before the stroke is material of the stroke
                    if (bounds.keepPlacement() && before != ColumnGrid.NONE && surface > before) continue;

                    // to a height map, a cliff face is a slope. Without the floor, open ground far below the shape is
                    // averaged against the shape and rises toward the shape
                    if (bounds.keepPlacement() && bounds.blendFloor() != ColumnGrid.NONE
                        && surface < bounds.blendFloor()) {
                        continue;
                    }

                    // with keepPlacement, the blend only adds. A cut of the ground around a kept shape removes
                    // scattered ground blocks on a rough cliff face, away from the shape
                    if (bounds.keepPlacement() && desired < surface) continue;

                    if (before != ColumnGrid.NONE && before < wasGround) wasGround = before;
                }

                final int lowest = (maxStep == Integer.MAX_VALUE) ? 0 : Math.max(0, wasGround - maxStep);
                final int highest = (maxStep == Integer.MAX_VALUE) ? MAX_Y : Math.min(MAX_Y, surface + maxStep);
                desired = Math.max(lowest, Math.min(highest, desired));

                if (desired < surface) {
                    // under the overhang of a shape on a slope, the target height can be in open air. Lower the column
                    // to the first ground at or below the target, or leave a column with no ground in reach
                    final int x = cx + dx;
                    final int z = cz + dz;
                    int groundY = ColumnGrid.NONE;
                    for (int y = Math.min(MAX_Y, desired); y >= lowest; y--) {
                        if (isGround(x, y, z)) {
                            groundY = y;
                            break;
                        }
                    }
                    if (groundY == ColumnGrid.NONE) continue;
                    desired = groundY;
                }

                reshapeColumn(new Vector(cx + dx, surface, cz + dz), desired);
            }
        }
    }

    /**
     * Sample the ground height of each column within {@code radius} of the centre, by {@link #terrainSurfaceY}.
     *
     * <p>
     * The probe of each column starts at the height of the previous column in the row. Terrain is continuous, so the
     * surface is usually a block or two from the probe. Each row starts from the first column with ground of the row
     * before: the scan returns to the far side of the disc at the end of each row.
     * </p>
     */
    public ColumnGrid sampleHeights(int radius) {
        final ColumnGrid heights = new ColumnGrid(radius);

        int rowStart = cy;
        for (int dz = -radius; dz <= radius; dz++) {
            int probe = rowStart;
            boolean rowSeeded = false;
            for (int dx = -radius; dx <= radius; dx++) {
                final int surface = terrainSurfaceY(cx + dx, cz + dz, probe);
                heights.set(dx, dz, surface);
                if (surface != ColumnGrid.NONE) {
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

    /**
     * Find the top block of the ground in a column, from the start height {@code nearY}, or return
     * {@link ColumnGrid#NONE} for a column with no ground.
     *
     * <p>
     * From a block of ground, the search climbs to the top of that mass of ground. From air, the search goes down. A
     * fixed search window from the centre of the brush would fail on steep terrain: above the window the scan would
     * start inside the hill and return a buried block, and below the window a column would have no surface. The climb
     * stops at the first air, so a bridge in the air above is not the ground.
     * </p>
     */
    private int terrainSurfaceY(int x, int z, int nearY) {
        int y = Math.max(0, Math.min(MAX_Y, nearY));

        if (isGround(x, y, z)) {
            while (y < MAX_Y && isGround(x, y + 1, z)) y++;
            return y;
        }

        while (y >= 0) {
            if (isGround(x, y, z)) return y;
            y--;
        }
        return ColumnGrid.NONE;
    }

    /**
     * Report whether the block is ground: not air, and not {@link Vegetation#isFoliage foliage}. With a check for air
     * only, a raise brush on a grassy hill stacks the tall grass and not the ground under the grass.
     */
    private boolean isGround(int x, int y, int z) {
        final BaseBlock block = ctx.getBlock(new Vector(x, y, z));
        return block.getType() != BlockID.AIR && !Vegetation.isFoliage(block);
    }

    // the mean of the 3 by 3 neighbourhood. A column with no ground is skipped, not counted as 0
    private double averageNeighbours(double[] heights, int ix, int iz) {
        double sum = 0.0D;
        int count = 0;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                final int nx = ix + ox;
                final int nz = iz + oz;
                if (nx < 0 || nz < 0 || nx >= width || nz >= width) continue;
                final double h = heights[nz * width + nx];
                if (Double.isNaN(h)) continue;
                sum += h;
                count++;
            }
        }
        return (count == 0) ? Double.NaN : sum / count;
    }

    /**
     * A smoothstep from 1 at the centre to 0 at {@code radius}, flat in the middle, so the result of repeated strokes
     * is round.
     */
    private static double falloff(double distance, double radius) {
        if (distance >= radius) return 0.0D;
        final double t = 1.0D - (distance / radius);
        return t * t * (3.0D - 2.0D * t);
    }

    /**
     * Move the top of a column from {@code surfaceBlock} to the height {@code desired}, with the surface block on top.
     *
     * <p>
     * Terrain has a surface block over a filler, such as grass over dirt or sand over sandstone. The surface block goes
     * to the new top, and the filler is the block under {@code surfaceBlock}. With
     * {@link BrushParams#useSelectedMaterial} both are the brush material. The rule uses no table of block ids, so the
     * rule applies to modded terrain too.
     * </p>
     */
    private void reshapeColumn(Vector surfaceBlock, int desired) throws MaxChangedBlocksException {
        final int x = surfaceBlock.getBlockX();
        final int surface = surfaceBlock.getBlockY();
        final int z = surfaceBlock.getBlockZ();
        if (desired == surface) return;

        final int newTop = Math.max(0, Math.min(MAX_Y, desired));
        final BaseBlock skin = params.useSelectedMaterial() ? params.material() : ctx.getBlock(surfaceBlock);

        BaseBlock filler = skin;
        if (!params.useSelectedMaterial() && surface > 0) {
            final BaseBlock below = ctx.getBlock(new Vector(x, surface - 1, z));
            if (below.getType() != BlockID.AIR && !Vegetation.isFoliage(below)) {
                filler = below;
            }
        }

        if (newTop > surface) {
            for (int y = surface; y < newTop; y++) {
                ctx.setBlock(new Vector(x, y, z), filler);
            }
        } else {
            // foliage on top of `surfaceBlock` floats after the cut, so remove the foliage. A raise fills over foliage
            for (int y = surface + 1; y <= MAX_Y; y++) {
                final BaseBlock above = ctx.getBlock(new Vector(x, y, z));
                if (above.getType() == BlockID.AIR || !Vegetation.isFoliage(above)) break;
                ctx.setAir(x, y, z);
            }

            for (int y = surface; y > newTop; y--) {
                ctx.setAir(x, y, z);
            }
        }

        ctx.setBlock(new Vector(x, newTop, z), skin);
    }

    /** Drop each block in each brush column straight down, onto the bottom of the brush or the next block below. */
    public void gravity() throws MaxChangedBlocksException {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (falloff(Math.sqrt(dx * dx + (double) dz * dz), r) <= 0.0D) continue;

                final int x = cx + dx;
                final int z = cz + dz;
                final int floor = cy - r;
                final int ceiling = cy + r;

                // read the whole column before the first write. A read in the write pass returns blocks already moved
                final List<BaseBlock> stack = new ArrayList<>();
                for (int y = floor; y <= ceiling; y++) {
                    if (y < 0 || y > MAX_Y) continue;
                    final BaseBlock block = ctx.getBlock(new Vector(x, y, z));
                    if (block.getType() != BlockID.AIR) {
                        stack.add(new BaseBlock(block));
                        ctx.setAir(x, y, z);
                    }
                }

                int y = Math.max(0, floor);
                for (BaseBlock block : stack) {
                    if (y > MAX_Y) break;
                    ctx.setBlock(new Vector(x, y, z), block);
                    y++;
                }
            }
        }
    }

    /** Raise the surface for a {@code sign} of 1, or lower the surface for -1, by up to radius times strength. */
    public void raiseLower(int sign) throws MaxChangedBlocksException {
        // the lift scales with the radius, so a bigger brush makes a bigger hill and not a wider step
        final double maxLift = Math.max(1.0D, r * params.strength());
        final ColumnGrid heights = sampleHeights(r);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                final int amount = (int) Math.round(maxLift * weight);
                if (amount <= 0) continue;

                final int surface = heights.get(dx, dz);
                if (surface == ColumnGrid.NONE) continue;

                reshapeColumn(new Vector(cx + dx, surface, cz + dz), surface + sign * amount);
            }
        }
    }

    /** Move the surface toward the Y of the centre, by the falloff times the strength. */
    public void flatten() throws MaxChangedBlocksException {
        final ColumnGrid heights = sampleHeights(r);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double weight = falloff(Math.sqrt(dx * dx + (double) dz * dz), r);
                if (weight <= 0.0D) continue;

                final int surface = heights.get(dx, dz);
                if (surface == ColumnGrid.NONE) continue;

                final int desired = (int) Math.round(surface + (cy - surface) * weight * params.strength());
                reshapeColumn(new Vector(cx + dx, surface, cz + dz), desired);
            }
        }
    }

    /**
     * Erode: remove each solid block with fewer solid side neighbours than a threshold. Melt: fill each air block with
     * at least the threshold of solid side neighbours. The threshold is from 2 to 5. With a higher strength, the
     * threshold of erode is higher and the threshold of melt is lower.
     */
    public void erode(boolean melt) throws MaxChangedBlocksException {
        final int threshold = melt ? (int) Math.round(2 + 3 * (1.0D - params.strength()))
            : (int) Math.round(2 + 3 * params.strength());

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (falloff(Math.sqrt(dx * dx + (double) dy * dy + (double) dz * dz), r) <= 0.0D) continue;

                    final int x = cx + dx;
                    final int y = cy + dy;
                    final int z = cz + dz;
                    if (y < 0 || y > MAX_Y) continue;

                    final boolean solid = !ctx.isAir(x, y, z);
                    if (melt == solid) continue;

                    final int neighbours = countSolidNeighbours(x, y, z);

                    if (!melt && solid && neighbours < threshold) {
                        ctx.setAir(x, y, z);
                    } else if (melt && !solid && neighbours >= threshold) {
                        final BaseBlock fill = params.useSelectedMaterial() ? params.material()
                            : findSolidNeighbour(x, y, z);
                        if (fill != null) ctx.setBlock(new Vector(x, y, z), fill);
                    }
                }
            }
        }
    }

    private int countSolidNeighbours(int x, int y, int z) {
        int count = 0;
        if (!ctx.isAir(x + 1, y, z)) count++;
        if (!ctx.isAir(x - 1, y, z)) count++;
        if (y < MAX_Y && !ctx.isAir(x, y + 1, z)) count++;
        if (y > 0 && !ctx.isAir(x, y - 1, z)) count++;
        if (!ctx.isAir(x, y, z + 1)) count++;
        if (!ctx.isAir(x, y, z - 1)) count++;
        return count;
    }

    // below first, then the four sides, then above. Melt fills with the material around the gap
    private BaseBlock findSolidNeighbour(int x, int y, int z) {
        final int[][] offsets = { { 0, -1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 } };
        for (int[] offset : offsets) {
            final int ny = y + offset[1];
            if (ny < 0 || ny > MAX_Y) continue;
            final BaseBlock block = ctx.getBlock(new Vector(x + offset[0], ny, z + offset[2]));
            if (block.getType() != BlockID.AIR) return block;
        }
        return null;
    }

    /**
     * Plant trees of the species the biome grows, from {@code BiomeGenBase.func_150567_a()}, which modded biomes
     * override.
     *
     * <p>
     * The tree generator writes to a {@link SculptorGenWorld}, and {@link SculptorGenWorld} writes to the context. So
     * the preview and the undo of a tree work as for any other stroke. Each random seed comes from a position: the
     * preview and the commit are two runs, and an unseeded generator grows a different tree in each run.
     * </p>
     */
    public void plantTrees() throws MaxChangedBlocksException {
        final int treeRadius = Math.max(0, r - 1);
        final double areaPerTree = 6.0D;
        final int attempts = Math.max(1, (int) Math.round((treeRadius * treeRadius) / areaPerTree * params.strength()));

        final Random placement = new Random(computeSeed(cx, cy, cz));
        final SculptorGenWorld genWorld = new SculptorGenWorld(ctx);
        int skipped = 0;

        for (int i = 0; i < attempts; i++) {
            final int x = (treeRadius == 0) ? cx : cx + placement.nextInt(2 * treeRadius + 1) - treeRadius;
            final int z = (treeRadius == 0) ? cz : cz + placement.nextInt(2 * treeRadius + 1) - treeRadius;

            if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > treeRadius * treeRadius) continue;

            final int surface = terrainSurfaceY(x, z, cy);
            if (surface == ColumnGrid.NONE || surface >= MAX_Y - 1) continue;

            final BiomeGenBase biome = genWorld.getBiomeGenForCoords(x, z);
            if (biome == null) continue;

            // a seed from the tree position, so the tree at a position does not depend on the earlier attempts
            final Random treeRandom = new Random(computeSeed(x, surface, z));
            final WorldGenAbstractTree generator = biome.func_150567_a(treeRandom);
            if (generator == null) continue;

            // a biome returns a shared generator object. During a call of WorldGenBigTree, a field of the generator
            // refers to the world, and Forge sets the field to null at the end. In singleplayer, a preview on the
            // client thread and a commit on the server thread can be in one generator at once, and one call then reads
            // the null world. The lock covers those two callers only. Vanilla chunk population uses the generators with
            // no lock, so skip a tree whose generator throws
            synchronized (generator) {
                // vanilla grows a tree only on grass, dirt or farmland. setSoilOverride() reports dirt at the surface
                genWorld.setSoilOverride(x, surface, z);
                try {
                    generator.generate(genWorld, treeRandom, x, surface + 1, z);
                } catch (RuntimeException exception) { // noqa: NAR142, tree generator code of other mods
                    SculptorMod.LOG.debug("trees.skipped x={} y={} z={}", x, surface, z, exception);
                    skipped++;
                } finally {
                    genWorld.clearSoilOverride();
                }
            }
        }

        if (skipped > 0) {
            // a skipped tree can differ between the preview and the commit
            SculptorMod.LOG.warn("trees.skipped skipped={} attempts={}", skipped, attempts);
        }
    }

    private static long computeSeed(int x, int y, int z) {
        long seed = x * 341873128712L + z * 132897987541L + y * 6364136223846793005L;
        seed ^= (seed >>> 33);
        return seed;
    }

    /** Remove the {@link Vegetation#isVegetation vegetation} in the brush sphere. */
    public void removeFoliage() throws MaxChangedBlocksException {
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (falloff(Math.sqrt(dx * dx + (double) dy * dy + (double) dz * dz), r) <= 0.0D) continue;

                    final int y = cy + dy;
                    if (y < 0 || y > MAX_Y) continue;

                    final Vector position = new Vector(cx + dx, y, cz + dz);
                    if (Vegetation.isVegetation(ctx.getBlock(position))) {
                        ctx.setBlock(position, AIR);
                    }
                }
            }
        }
    }

    /**
     * Fill down from the bottom of each column of a placed shape to the ground below, when the shape rests on the
     * ground in at least one column.
     *
     * <p>
     * A shape on a slope hangs over the ground on the downhill side, and the smoothing pass is a height map with no
     * data under the shape. A shape that touches the ground nowhere, such as a cylinder in the sky, stays as drawn. A
     * shape on a hillside fills down as far as the hillside falls away. A distance limit of twice the radius would
     * leave a large brush floating on a slope where a small brush settles.
     * </p>
     */
    public void settleOntoGround(ColumnGrid groundBefore) throws MaxChangedBlocksException {
        final double rSq = (r + 0.5D) * (r + 0.5D);

        // the bottom of the solid run in each column of the disc
        final ColumnGrid bottoms = new ColumnGrid(r);
        boolean resting = false;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + (double) dz * dz > rSq) continue;

                final int wasGround = groundBefore.get(dx, dz);
                if (wasGround == ColumnGrid.NONE) continue;

                int bottom = terrainSurfaceY(cx + dx, cz + dz, cy);
                if (bottom == ColumnGrid.NONE) continue;
                while (bottom > 0 && isGround(cx + dx, bottom - 1, cz + dz)) bottom--;

                bottoms.set(dx, dz, bottom);
                if (bottom <= wasGround) resting = true;
            }
        }

        if (!resting) return;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final int bottom = bottoms.get(dx, dz);
                if (bottom == ColumnGrid.NONE) continue;

                final int wasGround = groundBefore.get(dx, dz);
                for (int y = bottom - 1; y > wasGround; y--) {
                    ctx.setBlock(new Vector(cx + dx, y, cz + dz), params.material());
                }
            }
        }
    }

    /**
     * Curve the underside of an overhang down to the anchor ground, and leave the free edge hanging.
     *
     * <p>
     * A column of the shape is an anchor when the column rests on the ground, or when the ground of a side neighbour
     * stood within the thickness of the shape below the bottom of the column before the stroke. For each other column,
     * {@code along} is the distance from the column to the free edge, over the sum of the distances to the edge and to
     * the nearest anchor: 0 at the edge and 1 at the anchor. The bottom of the column moves down by up to
     * {@code depthFactor} times the thickness of the column, eased by {@code along} and scaled by the auto-smooth
     * setting. The fill never goes below the ground before the stroke, and stops at the first block that is not air.
     * </p>
     */
    public void cantileverOntoGround(ColumnGrid groundBefore, ShapeProfile shape, double depthFactor)
        throws MaxChangedBlocksException {
        final ColumnGrid shapeBottom = shape.bottom();
        final ColumnGrid shapeTop = shape.top();
        final int cells = shapeBottom.getCellCount();

        final int[] fromAnchor = new int[cells];
        Arrays.fill(fromAnchor, Integer.MAX_VALUE);
        final boolean[] resting = new boolean[cells];
        final Deque<Integer> anchors = new ArrayDeque<>();
        boolean anyOverhang = false;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final int base = shapeBottom.get(dx, dz);
                if (base == ColumnGrid.NONE) continue;

                final int index = shapeBottom.indexOf(dx, dz);
                final int wasGround = groundBefore.get(dx, dz);
                final int thickness = shapeTop.get(dx, dz) - base + 1;

                resting[index] = wasGround != ColumnGrid.NONE && base <= wasGround;
                if (!resting[index]) anyOverhang = true;

                // support beside the column counts down to the reach of the sweep, the thickness of the shape. A shape
                // at a cliff edge often stands a block or two above the cliff top
                if (resting[index] || groundBefore.hasNeighbourAtOrAbove(dx, dz, base - thickness)) {
                    fromAnchor[index] = 0;
                    anchors.add(index);
                }
            }
        }

        if (!anyOverhang || anchors.isEmpty()) return;

        // a blur of the underside does nothing. The base of the shape is level with the anchor ground, so the underside
        // and the ground have the same height, and the mean moves nothing. A cantilever varies in reach, not in height
        shapeBottom.spreadDistances(anchors, fromAnchor);

        final int[] fromRim = new int[cells];
        Arrays.fill(fromRim, Integer.MAX_VALUE);
        final Deque<Integer> rim = new ArrayDeque<>();
        for (int index = 0; index < cells; index++) {
            if (shapeBottom.getAt(index) == ColumnGrid.NONE) continue;
            if (shapeBottom.isEdge(index)) {
                fromRim[index] = 0;
                rim.add(index);
            }
        }
        shapeBottom.spreadDistances(rim, fromRim);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final int index = shapeBottom.indexOf(dx, dz);
                final int base = shapeBottom.getAt(index);
                if (base == ColumnGrid.NONE || resting[index]) continue;

                final int toAnchor = fromAnchor[index];
                final int toRim = fromRim[index];
                if (toAnchor == Integer.MAX_VALUE || toRim == Integer.MAX_VALUE) continue;
                if (toAnchor + toRim == 0) continue;

                // the two distances of the column normalise `along`, so the curve spans the whole overhang at any width
                final double along = toRim / (double) (toAnchor + toRim);
                final double eased = along * along * (3.0D - 2.0D * along);

                final int thickness = shapeTop.getAt(index) - base + 1;
                final int depth = Math.max(1, (int) Math.round(thickness * depthFactor));

                final int wasGround = groundBefore.get(dx, dz);
                int floor = base - depth;
                if (wasGround != ColumnGrid.NONE) floor = Math.max(floor, wasGround);

                int target = (int) Math.round(base - (base - floor) * eased * params.autoSmooth());
                target = Math.max(floor, Math.min(base, target));

                for (int y = base - 1; y >= target; y--) {
                    // stop the fill at the first block that is not air: the shape rests on that block
                    if (!ctx.isAir(cx + dx, y, cz + dz)) break;
                    ctx.setBlock(new Vector(cx + dx, y, cz + dz), params.material());
                }
            }
        }
    }
}
