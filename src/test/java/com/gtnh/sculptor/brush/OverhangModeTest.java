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

    /**
     * Level ground up to the origin, then a sheer drop -- a mountainside, which is where this
     * matters. On a gentle slope the top-surface taper reaches the ground on its own and thins
     * the outer edge legitimately, so there is no free edge left to say anything about.
     */
    private static final int DROP = 45;

    private static class SlopeWorld extends NullWorld {

        static int topAt(int x) {
            return (x <= 0) ? CENTRE_Y : CENTRE_Y - DROP;
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

    private static int bottomOf(SculptorEditContext ctx, int x, int z) {
        int y = topOf(ctx, x, z);
        if (y == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        while (y > 0 && !ctx.isAir(new Vector(x, y - 1, z))) y--;
        return y;
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

    /** The shape's own height above the ground in this column, or 0 where it placed nothing. */
    private static int slabHeightAt(SculptorEditContext ctx, int x, int z) {
        int top = topOf(ctx, x, z);
        int ground = SlopeWorld.topAt(x);
        return (top == Integer.MIN_VALUE || top <= ground) ? 0 : top - ground;
    }

    /**
     * The shape of the underside is the whole point, so this checks the curve rather than just
     * that something happened.
     *
     * <p>
     * The underside meets the slope where the shape springs from it, and sweeps away from the
     * ground as it reaches out, leaving the free edge standing at the shape's own full height.
     * Thinning the free edge instead is the same curve upside down and gives a wedge tapering to
     * nothing, which is not a ledge -- and leaving the underside flat past a fixed distance gives
     * a slab hanging over a void, which is what the first two attempts did.
     * </p>
     */
    @Test
    public void theCantileverUndersideSweepsFromTheSlopeToAFullHeightEdge() throws Exception {
        SculptorEditContext ctx = cylinder(OverhangMode.CANTILEVER);

        int previous = -1;
        int nearest = -1;
        int furthest = -1;
        int furthestX = 0;

        for (int dx = 1; dx <= RADIUS; dx++) {
            if (slabHeightAt(ctx, dx, 0) == 0) continue;

            int gap = gapUnder(ctx, dx, 0);
            if (nearest < 0) nearest = gap;
            furthest = gap;
            furthestX = dx;

            assertTrue(
                "the underside dips back toward the ground at x=" + dx + " (gap " + gap + " after " + previous + ")",
                gap >= previous);
            previous = gap;
        }

        assertTrue("no overhang was produced, so this proves nothing", furthestX > 0);
        assertEquals("the underside should meet the slope where the shape springs from it", 0, nearest);
        assertTrue("the underside should sweep away from the ground as it reaches out", furthest > 0);

        // The free edge keeps the shape's height rather than tapering away to a wedge.
        int edgeHeight = topOf(ctx, furthestX, 0) - bottomOf(ctx, furthestX, 0) + 1;
        assertTrue(
            "the free edge was thinned to " + edgeHeight + " blocks instead of keeping its height",
            edgeHeight >= 5);
    }

    /**
     * A column joined to the hillside has no underside -- walking its solid run down goes through
     * the terrain to bedrock. Reading that as the shape's base once made the pass strip a hundred
     * and twenty blocks of hillside out from under itself.
     *
     * <p>
     * Bounded by the auto-smooth leash rather than by zero, because the top-surface pass is
     * separately entitled to cut that far into untouched ground while blending the seam. A leash
     * of four still catches a hundred and twenty.
     * </p>
     */
    @Test
    public void theCantileverNeverDigsOutTheGroundBeneathIt() throws Exception {
        int leash = Math.max(2, Math.round(RADIUS / 3.0F));
        SculptorEditContext ctx = cylinder(OverhangMode.CANTILEVER);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int ground = SlopeWorld.topAt(dx);

                int surviving = ground;
                while (surviving > 0 && ctx.isAir(new Vector(dx, surviving, dz))) surviving--;

                assertTrue(
                    "ground at (" + dx + "," + dz + ") was dug out from " + ground + " down to " + surviving,
                    ground - surviving <= leash);
            }
        }
    }
}
