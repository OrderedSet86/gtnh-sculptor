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

}
