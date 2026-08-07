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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.gtnh.sculptor.edit.PlacementMask;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * Behaviour of individual brushes, as distinct from preview-vs-commit equivalence.
 */
public class BrushBehaviourTest {

    private static final int SURFACE_Y = 63;

    /** Grass over dirt over stone, so layering is observable. */
    private static class LayeredWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int y = position.getBlockY();
            if (y > SURFACE_Y) return new BaseBlock(BlockID.AIR);
            if (y == SURFACE_Y) return new BaseBlock(BlockID.GRASS);
            if (y >= SURFACE_Y - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    /**
     * A cliff far taller than any brush radius: ground at {@code y=63} west of {@code x=0} and at
     * {@code y=93} from there east.
     *
     * <p>
     * Thirty blocks of relief is the point. Surface finding used to search a window of
     * {@code centreY +/- (radius + 1)}, so on terrain like this it either started inside the cliff
     * and reported a buried block as the surface, or missed the low ground entirely -- and every
     * test in this file ran on flat ground, which is exactly why neither showed up.
     * </p>
     */
    private static final int LOW_Y = 63;
    private static final int HIGH_Y = 93;

    private static class CliffWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int top = (position.getBlockX() >= 0) ? HIGH_Y : LOW_Y;
            int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    /**
     * Flat ground with a plant standing on every column.
     *
     * <p>
     * Double plants are used because {@code isFoliage} recognises them by block id before it
     * consults the block registry, and the registry is not bootstrapped headlessly -- a grass tuft
     * would read as solid terrain here and prove nothing.
     * </p>
     */
    private static class PlantedWorld extends NullWorld {

        @Override
        public BaseBlock getBlock(Vector position) {
            int y = position.getBlockY();
            if (y > SURFACE_Y + 1) return new BaseBlock(BlockID.AIR);
            if (y == SURFACE_Y + 1) return new BaseBlock(BlockID.DOUBLE_PLANT);
            if (y == SURFACE_Y) return new BaseBlock(BlockID.GRASS);
            if (y >= SURFACE_Y - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static BrushParams params(int radius, double strength) {
        return new BrushParams(radius, new BaseBlock(BlockID.STONE), 1, strength, 0.0D, false);
    }

    /**
     * Raising grassy ground should not produce a solid block of grass. The old surface is buried
     * and becomes filler; the surface block is re-laid on the new top.
     */
    @Test
    public void raiseKeepsTheSurfaceBlockOnTopAndBuriesTheRestAsFiller() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.RAISE.apply(ctx, new Vector(0, SURFACE_Y, 0), params(4, 1.0D));

        Map<BlockVector, BaseBlock> changes = ctx.getPendingChanges();

        // The centre column is fully weighted, so it rises furthest.
        int top = Integer.MIN_VALUE;
        for (BlockVector pos : changes.keySet()) {
            if (pos.getBlockX() == 0 && pos.getBlockZ() == 0) {
                top = Math.max(top, pos.getBlockY());
            }
        }
        assertTrue("centre column should have risen", top > SURFACE_Y);

        assertEquals(
            "new top should be the surface block",
            BlockID.GRASS,
            changes.get(new BlockVector(0, top, 0))
                .getType());

        for (int y = SURFACE_Y; y < top; y++) {
            BaseBlock buried = changes.get(new BlockVector(0, y, 0));
            assertEquals("buried layer at y=" + y + " should be filler, not surface", BlockID.DIRT, buried.getType());
        }
    }

    /**
     * The placement mask governs what may be built over, so it must not stop a brush removing
     * anything. With air-only selected, every carving brush would otherwise be silently inert --
     * they write air, and air is never placeable over a block that is not already air.
     */
    @Test
    public void airOnlyMaskDoesNotBlockRemoval() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        ctx.setPlacementMask(PlacementMask.AIR_ONLY);

        assertTrue(
            "removing a solid block must be allowed regardless of the placement mask",
            ctx.setBlock(new Vector(0, SURFACE_Y, 0), new BaseBlock(BlockID.AIR)));

        SculptorEditContext lowering = SculptorEditContext.preview(new LayeredWorld());
        lowering.setPlacementMask(PlacementMask.AIR_ONLY);
        SculptorBrush.LOWER.apply(lowering, new Vector(0, SURFACE_Y, 0), params(3, 1.0D));

        assertTrue(
            "lower must still carve with an air-only mask set",
            !lowering.getPendingChanges()
                .isEmpty());
    }

    /**
     * Auto-smooth has to reach past the brush, otherwise it rounds the new shape in isolation
     * and leaves the seam where it meets untouched ground.
     *
     * <p>
     * Checked on a placement brush rather than raise. Raise already tapers with a smoothstep
     * falloff, so on even ground it produces a profile whose 3x3 mean is itself -- there is
     * genuinely nothing left to smooth. A sphere dropped into the ground is the case that leaves
     * a hard edge, and the one the pass exists for.
     * </p>
     */
    @Test
    public void autoSmoothAffectsGroundBeyondTheBrushRadius() throws Exception {
        int radius = 4;

        SculptorEditContext plain = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.SPHERE.apply(plain, new Vector(0, SURFACE_Y, 0), params(radius, 1.0D));

        SculptorEditContext smoothed = SculptorEditContext.preview(new LayeredWorld());
        BrushParams withSmoothing = new BrushParams(radius, new BaseBlock(BlockID.STONE), 1, 1.0D, 0.8D, false);
        SculptorBrush.SPHERE.apply(smoothed, new Vector(0, SURFACE_Y, 0), withSmoothing);

        assertTrue(
            "auto-smooth should have touched more than the placement alone",
            smoothed.getPendingChanges()
                .size()
                > plain.getPendingChanges()
                    .size());

        int furthest = 0;
        for (BlockVector pos : smoothed.getPendingChanges()
            .keySet()) {
            furthest = Math.max(furthest, Math.abs(pos.getBlockX()));
        }
        assertTrue("smoothing should reach beyond the brush radius, got " + furthest, furthest > radius);
    }

    /**
     * Lowering must re-lay the surface block, not just cut the column away.
     *
     * <p>
     * Simply removing blocks exposes the filler underneath, leaving bare dirt on top of every
     * column the brush touched -- which is what a terraced, dirt-capped hillside looks like.
     * </p>
     */
    @Test
    public void lowerLeavesTheSurfaceBlockOnTopNotExposedFiller() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.LOWER.apply(ctx, new Vector(0, SURFACE_Y, 0), params(4, 1.0D));

        Map<BlockVector, BaseBlock> changes = ctx.getPendingChanges();
        assertTrue("lower should have changed something", !changes.isEmpty());

        // Walk each touched column down to its new top and check what is capping it.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = dx;
                int z = dz;
                int top = Integer.MIN_VALUE;
                for (int y = SURFACE_Y; y >= SURFACE_Y - 8; y--) {
                    if (!ctx.isAir(new Vector(x, y, z))) {
                        top = y;
                        break;
                    }
                }
                if (top == Integer.MIN_VALUE || top == SURFACE_Y) continue;

                assertEquals(
                    "column (" + x + "," + z + ") should be capped with the surface block",
                    BlockID.GRASS,
                    ctx.getBlock(new Vector(x, top, z))
                        .getType());
            }
        }
    }

    /**
     * A grass block that ends up buried must not stay grass.
     */
    @Test
    public void raiseDoesNotLeaveGrassBuriedUnderTheNewSurface() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.RAISE.apply(ctx, new Vector(0, SURFACE_Y, 0), params(4, 1.0D));

        int top = Integer.MIN_VALUE;
        for (int y = SURFACE_Y + 8; y >= SURFACE_Y; y--) {
            if (!ctx.isAir(new Vector(0, y, 0))) {
                top = y;
                break;
            }
        }
        assertTrue("centre column should have risen", top > SURFACE_Y);

        for (int y = SURFACE_Y; y < top; y++) {
            assertEquals(
                "buried block at y=" + y + " must not be grass",
                BlockID.DIRT,
                ctx.getBlock(new Vector(0, y, 0))
                    .getType());
        }
    }

    /**
     * Smoothing a slope must not hollow it out.
     *
     * <p>
     * Where a column's real top sat above the old search window, the downward scan began inside
     * the hill and handed back a buried block as the surface. Lowering then cleared from there
     * down and left everything above untouched -- an intact slab of terrain over a fresh cavity,
     * which is what aggressive smoothing on a steep face actually produced.
     * </p>
     */
    @Test
    public void smoothDoesNotCarveACavityUnderAnIntactRoof() throws Exception {
        int radius = 8;
        SculptorEditContext ctx = SculptorEditContext.preview(new CliffWorld());

        // Aimed at the low ground, so the cliff top is far above the old window.
        SculptorBrush.SMOOTH.apply(ctx, new Vector(-1, LOW_Y, 0), params(radius, 1.0D));

        assertTrue(
            "smooth should have changed something",
            !ctx.getPendingChanges()
                .isEmpty());

        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                int top = Integer.MIN_VALUE;
                for (int y = HIGH_Y + 4; y >= LOW_Y - 8; y--) {
                    if (!ctx.isAir(new Vector(x, y, z))) {
                        top = y;
                        break;
                    }
                }
                if (top == Integer.MIN_VALUE) continue;

                // Ground was solid all the way down before the stroke, so any air below the new
                // top is a hole that was carved under a roof rather than off it.
                for (int y = top; y >= LOW_Y - 8; y--) {
                    assertTrue(
                        "column (" + x + "," + z + ") has air at y=" + y + " under solid ground at y=" + top,
                        !ctx.isAir(new Vector(x, y, z)));
                }
            }
        }
    }

    /**
     * Raise must lift every column under the brush, not the subset that happens to fall in a
     * window.
     *
     * <p>
     * With the window anchored to the brush centre, ground more than {@code radius + 1} below it
     * reported no surface at all and was skipped -- so on a slope, whichever columns poked above
     * that boundary rose and their neighbours did not. That is the scatter of stray single blocks
     * a large-radius raise left behind, and there is no randomness in the brush to explain it.
     * </p>
     */
    @Test
    public void raiseLiftsGroundFarBelowTheBrushCentre() throws Exception {
        int radius = 10;
        SculptorEditContext ctx = SculptorEditContext.preview(new CliffWorld());

        // Aimed at the cliff top; the low ground is 30 blocks down, well outside the old window.
        SculptorBrush.RAISE.apply(ctx, new Vector(0, HIGH_Y, 0), params(radius, 1.0D));

        // Half a radius out on the low side: smoothstep(0.5) = 0.5, so a maxLift of 10 gives 5.
        int expectedTop = LOW_Y + 5;
        for (int dz = -1; dz <= 1; dz++) {
            assertTrue(
                "low ground at (-5," + dz + ") should have risen to y=" + expectedTop,
                !ctx.isAir(new Vector(-5, expectedTop, dz)));
            assertTrue(
                "low ground at (-5," + dz + ") should not have risen past y=" + expectedTop,
                ctx.isAir(new Vector(-5, expectedTop + 1, dz)));
        }
    }

    /**
     * Plants must not be left hanging where the ground under them was cut away.
     *
     * <p>
     * Surface finding looks past foliage to the ground below it, so foliage sits above the range
     * the lowering pass clears and used to survive the column dropping out from under it.
     * </p>
     */
    @Test
    public void lowerDoesNotLeaveFoliageFloating() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new PlantedWorld());
        SculptorBrush.LOWER.apply(ctx, new Vector(0, SURFACE_Y, 0), params(4, 1.0D));

        assertTrue("the plant over the centre column should be gone", ctx.isAir(new Vector(0, SURFACE_Y + 1, 0)));
    }

}
