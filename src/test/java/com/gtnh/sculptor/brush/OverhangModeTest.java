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

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * The two answers to a shape hanging over a slope.
 *
 * <p>
 * Filling to the ground turns the overhang into a solid mass standing on the hill. The cantilever
 * leaves it hanging and rounds its underside into the ground where it springs from, so it curves
 * down to meet the slope at the anchor and levels off into the shape's own flat base further out.
 * </p>
 */
public class OverhangModeTest {

    private static final int CENTRE_Y = 120;
    private static final int RADIUS = 12;

    /** Level ground up to the origin, then falling two blocks per step. */
    private static class SlopeWorld extends NullWorld {

        static int topAt(int x) {
            return (x <= 0) ? CENTRE_Y : CENTRE_Y - 2 * x;
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            int top = topAt(position.getBlockX());
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

    private static SculptorEditContext cylinder(OverhangMode mode) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld());
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), 10, 1.0D, 1.0D, false, mode));
        return ctx;
    }

    private static int topOf(SculptorEditContext ctx, int x, int z) {
        for (int y = 250; y >= 0; y--) {
            if (!ctx.isAir(new Vector(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Air blocks between the ground and the underside of what is standing above it. */
    private static int gapUnder(SculptorEditContext ctx, int x, int z) {
        int y = topOf(ctx, x, z);
        if (y == Integer.MIN_VALUE) return 0;
        while (y > 0 && !ctx.isAir(new Vector(x, y - 1, z))) y--;

        int gap = 0;
        while (y > 0 && ctx.isAir(new Vector(x, y - 1, z))) {
            gap++;
            y--;
        }
        return gap;
    }

    @Test
    public void fillingToGroundLeavesNothingHanging() throws Exception {
        SculptorEditContext ctx = cylinder(OverhangMode.SETTLE);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            assertEquals("column " + dx + " should rest on the ground", 0, gapUnder(ctx, dx, 0));
        }
    }

    /**
     * The shape of the underside is the whole point, so this checks the curve rather than just
     * that something happened: touching the ground at the anchor, opening up as it reaches out,
     * and never opening back up again on the way.
     */
    @Test
    public void theCantileverCurvesFromTheAnchorAndLevelsOff() throws Exception {
        SculptorEditContext ctx = cylinder(OverhangMode.CANTILEVER);

        int previous = -1;
        boolean touchedDown = false;
        boolean liftedOff = false;

        for (int dx = 1; dx <= RADIUS - 2; dx++) {
            int gap = gapUnder(ctx, dx, 0);

            if (gap == 0) touchedDown = true;
            if (gap > 0) liftedOff = true;

            assertTrue(
                "the underside dips back down at x=" + dx + " (gap " + gap + " after " + previous + ")",
                gap >= previous);
            previous = gap;
        }

        assertTrue("the cantilever should meet the ground where it springs from", touchedDown);
        assertTrue("the cantilever should still be hanging further out, not filled to ground", liftedOff);
    }

    /**
     * A column joined to the hillside has no underside -- walking its solid run down goes through
     * the terrain to bedrock. Reading that as the shape's base once made the pass strip a hundred
     * and twenty blocks of hillside out from under itself.
     */
    @Test
    public void theCantileverNeverCutsIntoTheGroundBeneathIt() throws Exception {
        SculptorEditContext ctx = cylinder(OverhangMode.CANTILEVER);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int ground = SlopeWorld.topAt(dx);
                assertTrue("ground at (" + dx + "," + dz + ") was cut away", !ctx.isAir(new Vector(dx, ground, dz)));
            }
        }
    }
}
