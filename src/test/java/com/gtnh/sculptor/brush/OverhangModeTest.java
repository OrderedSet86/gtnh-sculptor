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
 * The two {@link OverhangMode} results for a cylinder over a drop.
 *
 * <p>
 * {@link OverhangMode#SETTLE} fills the overhang down to the ground. {@link OverhangMode#CANTILEVER} leaves the
 * overhang hanging, and curves the underside down to the slope at the anchor. Further out, the underside levels off to
 * the flat base of the cylinder.
 * </p>
 */
public class OverhangModeTest {

    private static final int CENTRE_Y = 120;
    private static final int RADIUS = 12;

    // the cylinder height, which limits the drop of the underside
    private static final int HEIGHT = 10;

    // a drop less than HEIGHT, so the underside reaches the ground. overADeepDropTheCantileverMakesASoffitNotAWall()
    // tests a deeper drop
    private static final int DROP = 6;

    @Test
    public void settleLeavesNothingHanging() throws Exception {
        final SculptorEditContext ctx = runCylinder(1.0D, OverhangMode.SETTLE, DROP);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            assertEquals("column " + dx + " should rest on the ground", 0, measureGapUnder(ctx, dx, 0));
        }
    }

    /**
     * The gap under the underside is 0 at the anchor and grows toward the free edge, and the free edge is at least 5 of
     * the 10 blocks of the cylinder thick.
     *
     * <p>
     * The same curve upside down thins the free edge to a wedge. A flat underside past a fixed distance leaves a slab
     * over a void.
     * </p>
     */
    @Test
    public void cantileverUndersideSweepsFromTheSlopeToAFullHeightEdge() throws Exception {
        final SculptorEditContext ctx = runCylinder(1.0D, OverhangMode.CANTILEVER, DROP);

        int previous = -1;
        int nearest = -1;
        int furthest = -1;
        int furthestX = 0;

        for (int dx = 1; dx <= RADIUS; dx++) {
            if (measureSlabHeight(ctx, dx, 0) == 0) continue;

            final int gap = measureGapUnder(ctx, dx, 0);
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

        final int edgeHeight = findTopY(ctx, furthestX, 0) - findBottomY(ctx, furthestX, 0) + 1;
        assertTrue(
            "the free edge was thinned to " + edgeHeight + " blocks instead of keeping its height",
            edgeHeight >= 5);
    }

    /**
     * The cantilever leaves the top face of the shape as drawn, and blends the ground around the shape. A smoothing
     * pass over the top moves each column toward the terrain beside the column, up against the hill and down over the
     * drop, and makes the flat face lumpy.
     */
    @Test
    public void cantileverLeavesThePlacedShapeAloneAndBlendsOnlyTheGround() throws Exception {
        final SculptorEditContext raw = runCylinder(0.0D, OverhangMode.CANTILEVER, DROP);
        final SculptorEditContext smoothed = runCylinder(1.0D, OverhangMode.CANTILEVER, DROP);

        int placedColumns = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final int before = findTopY(raw, dx, dz);
                if (before == Integer.MIN_VALUE || before <= SlopeWorld.computeTopY(dx, DROP)) continue;

                placedColumns++;
                assertEquals(
                    "the top of the shape at (" + dx + "," + dz + ") was reworked by the smoothing pass",
                    before,
                    findTopY(smoothed, dx, dz));
            }
        }
        assertTrue("no shape was placed, so this proves nothing", placedColumns > 100);

        // a pass that does nothing also leaves the shape alone, so the ground around the shape must change
        final int reach = RADIUS + Math.max(3, RADIUS / 2);
        int groundColumnsMoved = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (findTopY(raw, dx, dz) != findTopY(smoothed, dx, dz)) groundColumnsMoved++;
            }
        }
        assertTrue("the surrounding ground was not blended at all", groundColumnsMoved > 0);
    }

    /**
     * Over a drop of 45 blocks, the underside curves down by at most the height of the cylinder. A curve down to the
     * ground would make a wall the depth of the drop. {@link OverhangMode#SETTLE} is the mode for a fill to the ground.
     */
    @Test
    public void overADeepDropTheCantileverMakesASoffitNotAWall() throws Exception {
        final int deep = 45;
        final SculptorEditContext ctx = runCylinder(1.0D, OverhangMode.CANTILEVER, deep);

        int deepest = 0;
        for (int dx = 1; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final int bottom = findBottomY(ctx, dx, dz);
                if (bottom == Integer.MIN_VALUE || bottom <= SlopeWorld.computeTopY(dx, deep)) continue;
                deepest = Math.max(deepest, CENTRE_Y - bottom);
            }
        }

        assertTrue("the underside did not curve down at all", deepest > 0);
        assertTrue(
            "the underside dropped " + deepest + " blocks, more than the shape height of " + HEIGHT,
            deepest <= HEIGHT);
    }

    /**
     * A span from a tower beside the span gets a curved underside, with no ground under any column. An arch is built
     * this way. A span 2 blocks from the tower touches nothing, and stays as drawn.
     */
    @Test
    public void spanIsAnchoredByTheTowerBesideIt() throws Exception {
        final SculptorEditContext abutting = runSpanFromTower(RADIUS + 1, CENTRE_Y + 50);

        int curved = 0;
        for (int dx = 1; dx <= 2 * RADIUS + 1; dx++) {
            if (findBottomY(abutting, dx, 0) < CENTRE_Y) curved++;
        }
        assertTrue("the span was left as a flat slab, underside untouched", curved > 0);

        final SculptorEditContext clear = runSpanFromTower(RADIUS + 3, CENTRE_Y + 50);
        for (int dx = 3; dx <= 2 * RADIUS + 3; dx++) {
            final int bottom = findBottomY(clear, dx, 0);
            if (bottom == Integer.MIN_VALUE) continue;
            assertEquals("a span touching nothing should keep the shape it was drawn with", CENTRE_Y, bottom);
        }
    }

    /**
     * Support beside the shape is an anchor down to the height of the shape below the base: from 0 to 9 blocks, each
     * value. A shape at a cliff edge often stands a block or two above the cliff top. Support 40 blocks below is not an
     * anchor.
     */
    @Test
    public void supportBesideTheShapeCountsWhenTheShapeStandsAboveTheSupport() throws Exception {
        for (int above = 0; above < HEIGHT; above++) {
            final int depth = measureSweepDepth(runSpanFromTower(RADIUS + 1, CENTRE_Y - above));
            assertTrue("standing " + above + " blocks above its support, the underside was left flat", depth > 0);
        }

        final int wayBelow = measureSweepDepth(runSpanFromTower(RADIUS + 1, CENTRE_Y - 4 * HEIGHT));
        assertEquals("support far below should not drag the underside down to it", 0, wayBelow);
    }

    /**
     * A cylinder of 10 under a ledge 30 blocks thick drops the underside by at most 10. For a column joined to the
     * ledge, the solid run of the column is the ledge. A drop from that run would hang a spike the thickness of the
     * ledge.
     */
    @Test
    public void shapeUnderALedgeDoesNotHangASpikeTheHeightOfTheLedge() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new RoofWorld());
        SculptorBrush.CYLINDER.apply(ctx, new Vector(8, CENTRE_Y, 0), createCantileverParams(HEIGHT, 1.0D));

        int deepest = CENTRE_Y;
        boolean sweptSomewhere = false;

        for (int dx = -RADIUS + 8; dx <= RADIUS + 8; dx++) {
            final int bottom = findBottomY(ctx, dx, 0);
            if (bottom == Integer.MIN_VALUE || bottom < CENTRE_Y - 79) continue;

            if (bottom < CENTRE_Y) sweptSomewhere = true;
            deepest = Math.min(deepest, bottom);
        }

        assertTrue("nothing was shaped at all, so this proves nothing", sweptSomewhere);
        assertTrue(
            "the underside dropped " + (CENTRE_Y - deepest) + " blocks under a " + HEIGHT + "-block shape",
            CENTRE_Y - deepest <= HEIGHT);
    }

    /**
     * A sphere is round below, so the underside of a sphere drops less than a cylinder of the same thickness. Against a
     * vertical wall no ground stops the drop, and only the depth limit does.
     */
    @Test
    public void sphereDroopsLessThanACylinderOfTheSameThickness() throws Exception {
        final int droopSphere = measureDroopAgainstTower(SculptorBrush.SPHERE, 0);
        final int droopCylinder = measureDroopAgainstTower(SculptorBrush.CYLINDER, 2 * RADIUS + 1);

        assertTrue("the sphere did not droop at all, so this proves nothing", droopSphere > 0);
        assertTrue(
            "sphere drooped " + droopSphere + " but a cylinder of the same thickness only " + droopCylinder,
            droopSphere < droopCylinder);
    }

    /**
     * The cantilever cuts no ground below the shape by more than the leash of the smoothing pass. A column joined to
     * the hillside has no underside: the solid run of the column goes down to bedrock, and a read of that run as the
     * base would cut into the hillside below the shape. The smoothing pass may cut the leash into the ground at the
     * seam, so the bound is the leash and not 0.
     */
    @Test
    public void cantileverNeverDigsOutTheGroundBelowIt() throws Exception {
        final int leash = Math.max(2, Math.round(RADIUS / 3.0F));
        final SculptorEditContext ctx = runCylinder(1.0D, OverhangMode.CANTILEVER, DROP);

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final int ground = SlopeWorld.computeTopY(dx, DROP);

                int surviving = ground;
                while (surviving > 0 && ctx.isAir(new Vector(dx, surviving, dz))) surviving--;

                assertTrue(
                    "ground at (" + dx + "," + dz + ") was dug out from " + ground + " down to " + surviving,
                    ground - surviving <= leash);
            }
        }
    }

    private static SculptorEditContext runCylinder(double autoSmooth, OverhangMode mode, int drop) throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld(drop));
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            createCantileverParams(HEIGHT, autoSmooth).toBuilder()
                .overhang(mode)
                .build());
        return ctx;
    }

    private static SculptorEditContext runSpanFromTower(int centreX, int towerTop) throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new TowerWorld(towerTop));
        SculptorBrush.CYLINDER.apply(ctx, new Vector(centreX, CENTRE_Y, 0), createCantileverParams(HEIGHT, 1.0D));
        return ctx;
    }

    // the drop of the underside below the base of the cylinder, in the columns where the cylinder stands. Wider, the
    // scan counts valley floor that is solid to bedrock
    private static int measureSweepDepth(SculptorEditContext ctx) {
        int deepest = CENTRE_Y;
        for (int dx = 1; dx <= 2 * RADIUS + 1; dx++) {
            if (findTopY(ctx, dx, 0) < CENTRE_Y) continue;

            final int bottom = findBottomY(ctx, dx, 0);
            if (bottom != Integer.MIN_VALUE) deepest = Math.min(deepest, bottom);
        }
        return CENTRE_Y - deepest;
    }

    /** Measure the greatest drop of the underside, against the same stroke with auto-smooth off. */
    private static int measureDroopAgainstTower(SculptorBrush brush, int secondary) throws Exception {
        final SculptorEditContext plain = SculptorEditContext.preview(new TowerWorld(CENTRE_Y + 40));
        final SculptorEditContext shaped = SculptorEditContext.preview(new TowerWorld(CENTRE_Y + 40));

        final Vector at = new Vector(RADIUS + 1, CENTRE_Y, 0);
        brush.apply(plain, at, createCantileverParams(secondary, 0.0D));
        brush.apply(shaped, at, createCantileverParams(secondary, 1.0D));

        int worst = 0;
        for (int dx = 1; dx <= 2 * RADIUS + 1; dx++) {
            final int before = findBottomY(plain, dx, 0);
            final int after = findBottomY(shaped, dx, 0);
            if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) continue;
            if (before < CENTRE_Y - 80) continue;
            worst = Math.max(worst, before - after);
        }
        return worst;
    }

    private static BrushParams createCantileverParams(int secondary, double autoSmooth) {
        return BrushParams.builder()
            .radius(RADIUS)
            .material(new BaseBlock(BlockID.STONE))
            .secondary(secondary)
            .strength(1.0D)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(false)
            .overhang(OverhangMode.CANTILEVER)
            .build();
    }

    /** The height of the shape above the ground in the column, or 0 for a column with no placed block. */
    private static int measureSlabHeight(SculptorEditContext ctx, int x, int z) {
        final int top = findTopY(ctx, x, z);
        final int ground = SlopeWorld.computeTopY(x, DROP);
        return (top == Integer.MIN_VALUE || top <= ground) ? 0 : top - ground;
    }

    /** The air blocks between the ground and the bottom of the top solid run of the column. */
    private static int measureGapUnder(SculptorEditContext ctx, int x, int z) {
        int y = findTopY(ctx, x, z);
        if (y == Integer.MIN_VALUE) return 0;
        while (y > 0 && !ctx.isAir(new Vector(x, y - 1, z))) y--;

        int gap = 0;
        while (y > 0 && ctx.isAir(new Vector(x, y - 1, z))) {
            gap++;
            y--;
        }
        return gap;
    }

    private static int findBottomY(SculptorEditContext ctx, int x, int z) {
        int y = findTopY(ctx, x, z);
        if (y == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        while (y > 0 && !ctx.isAir(new Vector(x, y - 1, z))) y--;
        return y;
    }

    private static int findTopY(SculptorEditContext ctx, int x, int z) {
        for (int y = 250; y >= 0; y--) {
            if (!ctx.isAir(new Vector(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Level ground at {@link #CENTRE_Y} for x up to 0, and {@code drop} blocks lower for x above 0. */
    private static class SlopeWorld extends NullWorld {

        private final int drop;

        SlopeWorld(int drop) {
            this.drop = drop;
        }

        static int computeTopY(int x, int drop) {
            return (x <= 0) ? CENTRE_Y : CENTRE_Y - drop;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int top = computeTopY(position.getBlockX(), drop);
            final int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }

    /** A stone tower for x up to 0, up to {@code towerTop}, over open air down to stone 90 blocks below the centre. */
    private static class TowerWorld extends NullWorld {

        private final int towerTop;

        TowerWorld(int towerTop) {
            this.towerTop = towerTop;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int y = position.getBlockY();
            if (position.getBlockX() <= 0 && y <= towerTop) return new BaseBlock(BlockID.STONE);
            return new BaseBlock(y <= CENTRE_Y - 90 ? BlockID.STONE : BlockID.AIR);
        }
    }

    /**
     * A ledge from y = {@code CENTRE_Y + HEIGHT} to {@link #ROOF_TOP} for x up to 6, over a support for x up to 0 whose
     * top is 3 blocks below the centre.
     *
     * <p>
     * The top of the support is below the base of the shape, so the height probe of a row drops to the valley at 80
     * blocks below the centre. A column joined to the ledge then has the valley floor as the ground.
     * </p>
     */
    private static class RoofWorld extends NullWorld {

        static final int ROOF_TOP = CENTRE_Y + 30;

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int x = position.getBlockX();
            final int y = position.getBlockY();

            if (x <= 0 && y <= CENTRE_Y - 3) return new BaseBlock(BlockID.STONE);
            if (x <= 6 && y >= CENTRE_Y + HEIGHT && y <= ROOF_TOP) return new BaseBlock(BlockID.STONE);
            return new BaseBlock(y <= CENTRE_Y - 80 ? BlockID.STONE : BlockID.AIR);
        }
    }
}
