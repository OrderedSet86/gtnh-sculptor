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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * What the pass after a placement brush may and may not do.
 *
 * <p>
 * The two halves pull in opposite directions, which is why they are tested together. A cylinder
 * standing nineteen blocks proud of a hillside has to be able to taper all the way back down to
 * the ground, so cutting the stroke's own material cannot be bounded. Reshaping the hillside
 * itself has to be, or the same mean that tapers the shape hollows out the slope beside it and
 * raises pillars out of the valley below. Bounding both was the first attempt and it left the
 * placement standing as a flat-topped mesa.
 * </p>
 */
public class AutoSmoothTest {

    private static final int RADIUS = 12;
    private static final int HEIGHT = 19;
    private static final int CENTRE_Y = 85;

    /** The leash in {@code SculptorBrush.apply}: two blocks, or a third of the radius. */
    private static final int LEASH = Math.max(2, Math.round(RADIUS / 3.0F));

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    /** A hillside falling one block per step in x, with three blocks of roughness on it. */
    private static class SlopeWorld extends NullWorld {

        static int topAt(int x, int z) {
            return CENTRE_Y - x + (Math.floorMod(hash(x, z), 7) - 3);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            int top = topAt(position.getBlockX(), position.getBlockZ());
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

    private static SculptorEditContext stroke(double autoSmooth) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld());
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, autoSmooth, false));
        return ctx;
    }

    private static int surfaceOf(SculptorEditContext ctx, int x, int z) {
        for (int y = 220; y >= 0; y--) {
            if (!ctx.isAir(new Vector(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * How deep a gap sits under this column's topmost run of solid blocks; zero if that run
     * reaches the ground.
     *
     * <p>
     * Checking only the single block under the surface is not enough and was wrong here once: a
     * nineteen-block slab hanging over a hillside has solid blocks under its top for eighteen of
     * them, and reports as perfectly well supported.
     * </p>
     */
    private static int gapUnder(SculptorEditContext ctx, int x, int z) {
        int y = surfaceOf(ctx, x, z);
        if (y == Integer.MIN_VALUE) return 0;

        while (y >= 0 && !ctx.isAir(new Vector(x, y, z))) y--;
        if (y < 0) return 0;

        int gap = 0;
        while (y >= 0 && ctx.isAir(new Vector(x, y, z))) {
            gap++;
            y--;
        }
        return (y < 0) ? 0 : gap;
    }

    private static int columnsLeftHanging(SculptorEditContext ctx, int span) {
        int hanging = 0;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                if (gapUnder(ctx, dx, dz) > 0) hanging++;
            }
        }
        return hanging;
    }

    /**
     * Measured against the same stroke with the pass switched off, so what is under test is the
     * smoothing rather than the cylinder it follows. Measuring against the untouched world instead
     * mostly measures the placement, which is how a 26-block "rise" got mistaken for a smoothing
     * bug once already.
     */
    @Test
    public void autoSmoothTapersThePlacementWithoutReshapingTheHillside() throws Exception {
        SculptorEditContext plain = stroke(0.0D);
        SculptorEditContext smoothed = stroke(1.0D);

        int span = RADIUS + Math.max(3, RADIUS / 2) + 2;

        int taper = 0;
        int dugBelowGround = 0;
        int raisedAboveGround = 0;
        int floating = 0;

        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int before = surfaceOf(plain, dx, dz);
                int after = surfaceOf(smoothed, dx, dz);
                if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) continue;

                int ground = SlopeWorld.topAt(dx, dz);

                taper = Math.max(taper, before - after);
                dugBelowGround = Math.max(dugBelowGround, ground - after);
                raisedAboveGround = Math.max(raisedAboveGround, after - Math.max(before, ground));

                floating += (gapUnder(smoothed, dx, dz) > 0) ? 1 : 0;
            }
        }

        assertTrue(
            "the pass must be free to shave the placement back to the ground; largest cut was only " + taper,
            taper > 2 * LEASH);

        assertTrue(
            "cut " + dugBelowGround + " blocks into ground that was already there, leash is " + LEASH,
            dugBelowGround <= LEASH);

        assertTrue(
            "raised a column " + raisedAboveGround + " blocks above the ground, leash is " + LEASH,
            raisedAboveGround <= LEASH);

        assertTrue("left " + floating + " columns hanging over a gap", floating == 0);
    }

    /**
     * A cylinder fills upward from the height it was aimed at, across its whole disc, so on a
     * slope the downhill half of it has nothing underneath. Smoothing cannot fix that -- it is a
     * height map, it moves the top of a column and never looks below it -- so the shape is
     * extended down onto the ground first.
     *
     * <p>
     * Tied to the auto-smooth slider on purpose. At zero a cylinder is exactly a cylinder,
     * hanging base and all, which is what building in the air needs.
     * </p>
     */
    @Test
    public void aShapeOnASlopeIsSettledOntoTheGroundOnlyWhenAutoSmoothIsOn() throws Exception {
        int span = RADIUS + Math.max(3, RADIUS / 2) + 2;

        int hangingWithout = columnsLeftHanging(stroke(0.0D), span);
        int hangingWith = columnsLeftHanging(stroke(1.0D), span);

        assertTrue(
            "the test slope should leave the plain cylinder hanging, otherwise this proves nothing; got "
                + hangingWithout,
            hangingWithout > 50);

        assertTrue("auto-smooth left " + hangingWith + " columns hanging over a gap", hangingWith == 0);
    }
}
