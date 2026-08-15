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

    /** The cylinder's own height, which is what bounds how far its underside may curve down. */
    private static final int HEIGHT = 10;

    /**
     * Level ground up to the origin, then a sheer drop. Shallow by default -- shallower than the
     * shape is tall -- because that is the case where the underside is expected to reach the
     * ground and merge into it. A drop deeper than the shape gets a soffit instead, which is a
     * separate test.
     */
    private static final int DROP = 6;

    private static class SlopeWorld extends NullWorld {

        private final int drop;

        SlopeWorld(int drop) {
            this.drop = drop;
        }

        static int topAt(int x) {
            return topAt(x, DROP);
        }

        static int topAt(int x, int drop) {
            return (x <= 0) ? CENTRE_Y : CENTRE_Y - drop;
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            int top = topAt(position.getBlockX(), drop);
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
        return cylinder(1.0D, mode);
    }

    private static SculptorEditContext cylinder(double autoSmooth, OverhangMode mode) throws Exception {
        return cylinder(autoSmooth, mode, DROP);
    }

    private static SculptorEditContext cylinder(double autoSmooth, OverhangMode mode, int drop) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld(drop));
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, autoSmooth, false, mode));
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
     * The cantilever keeps the face the shape was drawn with.
     *
     * <p>
     * Its underside has already been dealt with by then, and letting the height map work the top
     * over as well pulls each column toward whatever the terrain beside it happens to be doing --
     * up against the hill, down over the drop -- which carves the flat face into an island of
     * lumps. Filling to ground has no such face to preserve, and there the taper is the point.
     * </p>
     */
    @Test
    public void theCantileverLeavesThePlacedShapeAloneAndBlendsOnlyTheGround() throws Exception {
        SculptorEditContext raw = cylinder(0.0D, OverhangMode.CANTILEVER);
        SculptorEditContext smoothed = cylinder(1.0D, OverhangMode.CANTILEVER);

        int placedColumns = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int before = topOf(raw, dx, dz);
                if (before == Integer.MIN_VALUE || before <= SlopeWorld.topAt(dx)) continue;

                placedColumns++;
                assertEquals(
                    "the shape's top at (" + dx + "," + dz + ") was reworked by the smoothing pass",
                    before,
                    topOf(smoothed, dx, dz));
            }
        }
        assertTrue("no shape was placed, so this proves nothing", placedColumns > 100);

        // Ground beyond the shape is still blended, otherwise "leave the shape alone" would just
        // mean doing nothing at all.
        int reach = RADIUS + Math.max(3, RADIUS / 2);
        int groundColumnsMoved = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (topOf(raw, dx, dz) != topOf(smoothed, dx, dz)) groundColumnsMoved++;
            }
        }
        assertTrue("the surrounding ground was not blended at all", groundColumnsMoved > 0);
    }

    /**
     * Over a drop deeper than the shape is tall, the underside curves down by about the shape's
     * own height and stops.
     *
     * <p>
     * Following the ground however far away it is turns a soffit into a curtain wall -- measured
     * at eighty blocks for a ten-block cylinder placed off a tower. Filling to ground is the mode
     * for that; this one is not.
     * </p>
     */
    @Test
    public void overADeepDropTheCantileverMakesASoffitRatherThanACurtain() throws Exception {
        int deep = 45;
        SculptorEditContext ctx = cylinder(1.0D, OverhangMode.CANTILEVER, deep);

        int deepest = 0;
        for (int dx = 1; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int bottom = bottomOf(ctx, dx, dz);
                if (bottom == Integer.MIN_VALUE || bottom <= SlopeWorld.topAt(dx, deep)) continue;
                deepest = Math.max(deepest, CENTRE_Y - bottom);
            }
        }

        assertTrue("the underside did not curve down at all", deepest > 0);
        assertTrue(
            "the underside dropped " + deepest + " blocks, more than the shape's own height of " + HEIGHT,
            deepest <= HEIGHT);
    }

    /** A tower standing beside the shape rather than under it -- how an arch is built. */
    private static class TowerWorld extends NullWorld {

        private final int towerTop;

        TowerWorld(int towerTop) {
            this.towerTop = towerTop;
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            int y = position.getBlockY();
            if (position.getBlockX() <= 0 && y <= towerTop) return new BaseBlock(BlockID.STONE);
            return new BaseBlock(y <= CENTRE_Y - 90 ? BlockID.STONE : BlockID.AIR);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static SculptorEditContext spanFromTower(int centreX) throws Exception {
        return spanFromTower(centreX, CENTRE_Y + 50);
    }

    private static SculptorEditContext spanFromTower(int centreX, int towerTop) throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new TowerWorld(towerTop));
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(centreX, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, 1.0D, false, OverhangMode.CANTILEVER));
        return ctx;
    }

    /**
     * Support is not only what is underneath.
     *
     * <p>
     * An arch is built by spanning out from something standing beside the new shape rather than
     * below it. Defining the anchor as "this column reaches the ground" meant a span like that
     * found no anchor at all and was left as the flat slab it was drawn as, however carefully the
     * underside pass was tuned -- and the higher the span, the more certain that was.
     * </p>
     */
    @Test
    public void aSpanIsAnchoredByWhatStandsBesideItNotOnlyByWhatIsBelow() throws Exception {
        // Abutting the tower: nothing under any column, contact on one side only.
        SculptorEditContext abutting = spanFromTower(RADIUS + 1);

        int curved = 0;
        for (int dx = 1; dx <= 2 * RADIUS + 1; dx++) {
            if (bottomOf(abutting, dx, 0) < CENTRE_Y) curved++;
        }
        assertTrue("the span was left as a flat slab, underside untouched", curved > 0);

        // Two blocks clear of it, touching nothing: left exactly as drawn, as chosen.
        SculptorEditContext clear = spanFromTower(RADIUS + 3);
        for (int dx = 3; dx <= 2 * RADIUS + 3; dx++) {
            int bottom = bottomOf(clear, dx, 0);
            if (bottom == Integer.MIN_VALUE) continue;
            assertEquals("a span touching nothing should keep the shape it was drawn with", CENTRE_Y, bottom);
        }
    }

    /**
     * How far the underside was drawn down below the shape's base.
     *
     * <p>
     * Only columns the shape actually stands in are counted. Scanning wider picks up untouched
     * valley floor, which is solid to bedrock and reports as a hundred-block sweep.
     * </p>
     */
    private static int sweepDepth(SculptorEditContext ctx) {
        int deepest = CENTRE_Y;
        for (int dx = 1; dx <= 2 * RADIUS + 1; dx++) {
            if (topOf(ctx, dx, 0) < CENTRE_Y) continue;

            int bottom = bottomOf(ctx, dx, 0);
            if (bottom != Integer.MIN_VALUE) deepest = Math.min(deepest, bottom);
        }
        return CENTRE_Y - deepest;
    }

    /**
     * Support beside the shape counts even when it does not come all the way up to the underside.
     *
     * <p>
     * A shape placed at a cliff edge commonly stands a block or two proud of the top it springs
     * from. Requiring the neighbour to reach the underside exactly meant the entire pass switched
     * off over a difference nobody can see, which is what left a span looking untouched. The
     * support has to be near enough that the curve could reach it, and "near enough" is the same
     * distance the sweep is allowed: the shape's own height.
     * </p>
     */
    @Test
    public void supportBesideTheShapeCountsEvenWhenTheShapeStandsProudOfIt() throws Exception {
        for (int proud = 0; proud < HEIGHT; proud++) {
            int depth = sweepDepth(spanFromTower(RADIUS + 1, CENTRE_Y - proud));
            assertTrue("standing " + proud + " blocks proud of its support, the underside was left flat", depth > 0);
        }

        // Far enough below and there is nothing to blend into: left exactly as drawn.
        int wayBelow = sweepDepth(spanFromTower(RADIUS + 1, CENTRE_Y - 4 * HEIGHT));
        assertEquals("support far below should not drag the underside down to it", 0, wayBelow);
    }

    /**
     * A ledge overhead that the shape is placed beneath, with its support lower down to one side.
     *
     * <p>
     * The support's top is deliberately below the shape's base, so the height probe walking along
     * a row drops to the valley rather than carrying the ledge's height with it. That is what
     * makes a column fused to the ledge report the valley floor as its ground.
     * </p>
     */
    private static class RoofWorld extends NullWorld {

        static final int ROOF_TOP = CENTRE_Y + 30;

        @Override
        public BaseBlock getBlock(Vector position) {
            int x = position.getBlockX();
            int y = position.getBlockY();

            if (x <= 0 && y <= CENTRE_Y - 3) return new BaseBlock(BlockID.STONE);
            if (x <= 6 && y >= CENTRE_Y + HEIGHT && y <= ROOF_TOP) return new BaseBlock(BlockID.STONE);
            return new BaseBlock(y <= CENTRE_Y - 80 ? BlockID.STONE : BlockID.AIR);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    /**
     * A shape placed under an overhang must not hang a spike the height of the overhang.
     *
     * <p>
     * Two separate mistakes met here. The curve's depth was measured as the contiguous solid run
     * in the column, which for a shape fused to a ledge is the ledge; and such a column was read
     * as free-hanging at all, because the height probe walks along a row and can pass underneath
     * an overhang without seeing it, reporting the valley floor as the ground beneath it. A
     * ten-block cylinder under a thirty-block ledge dropped a thirty-one block spike.
     * </p>
     */
    @Test
    public void aShapePlacedUnderALedgeDoesNotHangASpikeTheHeightOfIt() throws Exception {
        SculptorEditContext ctx = SculptorEditContext.preview(new RoofWorld());
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(8, CENTRE_Y, 0),
            new BrushParams(RADIUS, new BaseBlock(BlockID.STONE), HEIGHT, 1.0D, 1.0D, false, OverhangMode.CANTILEVER));

        int deepest = CENTRE_Y;
        boolean sweptSomewhere = false;

        for (int dx = -RADIUS + 8; dx <= RADIUS + 8; dx++) {
            int bottom = bottomOf(ctx, dx, 0);
            if (bottom == Integer.MIN_VALUE || bottom < CENTRE_Y - 79) continue;

            // Columns fused to the ledge overhead are support, not overhang.
            if (dx >= 1 && dx <= 6) {
                assertEquals("column " + dx + " is fused to the ledge and should be left alone", CENTRE_Y, bottom);
            }

            if (bottom < CENTRE_Y) sweptSomewhere = true;
            deepest = Math.min(deepest, bottom);
        }

        assertTrue("nothing was shaped at all, so this proves nothing", sweptSomewhere);
        assertTrue(
            "the underside dropped " + (CENTRE_Y - deepest) + " blocks under a " + HEIGHT + "-block shape",
            CENTRE_Y - deepest <= HEIGHT);
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
