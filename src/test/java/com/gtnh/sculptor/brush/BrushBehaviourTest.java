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

/** The result of single brushes on small test worlds. {@code PreviewMatchesCommitTest} compares preview and commit. */
public class BrushBehaviourTest {

    private static final int SURFACE_Y = 63;
    private static final int LOW_Y = 63;
    private static final int HIGH_Y = 93;
    private static final int LEDGE_Y = 80;
    private static final int VALLEY_Y = 70;

    /** A raise of grassy ground puts the grass on the new top, and dirt under the grass. */
    @Test
    public void raiseKeepsTheSurfaceBlockOnTopAndBuriesTheRestAsFiller() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.RAISE.apply(ctx, new Vector(0, SURFACE_Y, 0), createParams(4, 1.0D));

        final Map<BlockVector, BaseBlock> changes = ctx.getPendingChanges();

        // the centre column has the full weight, and the highest top
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
            final BaseBlock buried = changes.get(new BlockVector(0, y, 0));
            assertEquals("buried layer at y=" + y + " should be filler, not surface", BlockID.DIRT, buried.getType());
        }
    }

    /**
     * The placement mask applies to placements and not to removals. A removal writes air, and {@code AIR_ONLY} allows
     * air only over air, so a mask on removals stops every carving brush.
     */
    @Test
    public void airOnlyMaskDoesNotBlockRemoval() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        ctx.setPlacementMask(PlacementMask.AIR_ONLY);

        assertTrue(
            "removing a solid block must be allowed regardless of the placement mask",
            ctx.setBlock(new Vector(0, SURFACE_Y, 0), new BaseBlock(BlockID.AIR)));

        final SculptorEditContext lowering = SculptorEditContext.preview(new LayeredWorld());
        lowering.setPlacementMask(PlacementMask.AIR_ONLY);
        SculptorBrush.LOWER.apply(lowering, new Vector(0, SURFACE_Y, 0), createParams(3, 1.0D));

        assertTrue(
            "lower must still carve with an air-only mask set",
            !lowering.getPendingChanges()
                .isEmpty());
    }

    /**
     * Auto-smooth reaches past the brush radius, to blend the seam between the new shape and the ground.
     *
     * <p>
     * The test uses a sphere and not raise. Raise tapers with a smoothstep falloff, so on even ground the 3 by 3 mean
     * of the raised ground is the same ground. A sphere in the ground leaves a hard edge.
     * </p>
     */
    @Test
    public void autoSmoothAffectsGroundBeyondTheBrushRadius() throws Exception {
        final int radius = 4;

        final SculptorEditContext plain = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.SPHERE.apply(plain, new Vector(0, SURFACE_Y, 0), createParams(radius, 1.0D));

        final SculptorEditContext smoothed = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.SPHERE.apply(smoothed, new Vector(0, SURFACE_Y, 0), createSmoothingParams(radius, 1.0D, 0.8D));

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

    /** Lower puts the surface block on the new top. A plain cut leaves bare dirt on each column. */
    @Test
    public void lowerLeavesTheSurfaceBlockOnTopNotExposedFiller() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.LOWER.apply(ctx, new Vector(0, SURFACE_Y, 0), createParams(4, 1.0D));

        final Map<BlockVector, BaseBlock> changes = ctx.getPendingChanges();
        assertTrue("lower should have changed something", !changes.isEmpty());

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
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

    @Test
    public void raiseDoesNotLeaveGrassBuriedUnderTheNewSurface() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new LayeredWorld());
        SculptorBrush.RAISE.apply(ctx, new Vector(0, SURFACE_Y, 0), createParams(4, 1.0D));

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
     * Smooth on a cliff does not carve a cavity under a roof of terrain. A surface search in a fixed window from the
     * centre would start inside the hill and return a buried block, and smooth would cut down from that block.
     */
    @Test
    public void smoothDoesNotCarveACavityUnderAnIntactRoof() throws Exception {
        final int radius = 8;
        final SculptorEditContext ctx = SculptorEditContext.preview(new CliffWorld());

        // the brush is on the low ground, and the cliff top is outside a window of radius + 1 from the centre
        SculptorBrush.SMOOTH.apply(ctx, new Vector(-1, LOW_Y, 0), createParams(radius, 1.0D));

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

                // each column was solid down from the top before the stroke, so air under the new top is a cavity
                for (int y = top; y >= LOW_Y - 8; y--) {
                    assertTrue(
                        "column (" + x + "," + z + ") has air at y=" + y + " under solid ground at y=" + top,
                        !ctx.isAir(new Vector(x, y, z)));
                }
            }
        }
    }

    /**
     * Raise lifts each column under the brush, also ground 30 blocks below the centre. With a surface search in a fixed
     * window, a column far below the centre would have no surface, and raise would skip the column.
     */
    @Test
    public void raiseLiftsGroundFarBelowTheBrushCentre() throws Exception {
        final int radius = 10;
        final SculptorEditContext ctx = SculptorEditContext.preview(new CliffWorld());

        SculptorBrush.RAISE.apply(ctx, new Vector(0, HIGH_Y, 0), createParams(radius, 1.0D));

        // half a radius out on the low side, smoothstep(0.5) = 0.5, so the lift of 10 at the centre is 5 here
        final int expectedTop = LOW_Y + 5;
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
     * Under {@code AIR_ONLY}, the auto-smooth pass removes no block that was there before the stroke. The player did
     * not choose the pass, and a column can go down in the pass.
     */
    @Test
    public void autoSmoothUnderAirOnlyDoesNotCutIntoExistingGround() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new CliffWorld());
        ctx.setPlacementMask(PlacementMask.AIR_ONLY);
        // beside the cliff, the smoothing disc reaches ground far above the placement
        SculptorBrush.SPHERE.apply(ctx, new Vector(-1, LOW_Y, 0), createSmoothingParams(4, 1.0D, 0.8D));

        final CliffWorld before = new CliffWorld();
        for (Map.Entry<BlockVector, BaseBlock> change : ctx.getPendingChanges()
            .entrySet()) {
            if (change.getValue()
                .getType() != BlockID.AIR) continue;

            assertEquals(
                "air-only must not remove the block that was already at " + change.getKey(),
                BlockID.AIR,
                before.getBlock(change.getKey())
                    .getType());
        }

        // a brush that changes nothing passes the loop above
        assertTrue("the sphere should still have been placed", !ctx.isAir(new Vector(-1, LOW_Y + 2, 0)));
    }

    /**
     * Smooth writes no solid block over air. The height map reads the top of a ledge as a surface, and a cut under the
     * ledge followed by a new surface block leaves a floating shelf.
     */
    @Test
    public void smoothLeavesUnsupportedLedgesAlone() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new LedgeWorld());
        SculptorBrush.SMOOTH.apply(ctx, new Vector(0, LEDGE_Y, 0), createParams(6, 1.0D));

        assertTrue(
            "smooth should have changed something",
            !ctx.getPendingChanges()
                .isEmpty());

        for (Map.Entry<BlockVector, BaseBlock> change : ctx.getPendingChanges()
            .entrySet()) {
            if (change.getValue()
                .getType() == BlockID.AIR) continue;

            final BlockVector pos = change.getKey();
            assertTrue(
                "solid block written at " + pos + " has nothing under it",
                !ctx.isAir(new Vector(pos.getBlockX(), pos.getBlockY() - 1, pos.getBlockZ())));
        }
    }

    /**
     * Lower removes the plant on each column that lower cuts. The surface search looks past foliage, so the plant is
     * above the cut.
     */
    @Test
    public void lowerDoesNotLeaveFoliageFloating() throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new PlantedWorld());
        SculptorBrush.LOWER.apply(ctx, new Vector(0, SURFACE_Y, 0), createParams(4, 1.0D));

        assertTrue("the plant over the centre column should be gone", ctx.isAir(new Vector(0, SURFACE_Y + 1, 0)));
    }

    private static BrushParams createParams(int radius, double strength) {
        return createSmoothingParams(radius, strength, 0.0D);
    }

    private static BrushParams createSmoothingParams(int radius, double strength, double autoSmooth) {
        return BrushParams.builder()
            .radius(radius)
            .material(new BaseBlock(BlockID.STONE))
            .secondary(1)
            .strength(strength)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(false)
            .build();
    }

    /** Grass over 3 blocks of dirt over stone, with the grass at {@link #SURFACE_Y}. */
    private static class LayeredWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int y = position.getBlockY();
            if (y > SURFACE_Y) return new BaseBlock(BlockID.AIR);
            if (y == SURFACE_Y) return new BaseBlock(BlockID.GRASS);
            if (y >= SURFACE_Y - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }

    /**
     * A cliff 30 blocks high, taller than any brush radius: ground at {@link #LOW_Y} for x below 0, and at
     * {@link #HIGH_Y} from x = 0.
     */
    private static class CliffWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int top = (position.getBlockX() >= 0) ? HIGH_Y : LOW_Y;
            final int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }

    /**
     * Flat ground with a double plant on each column. {@code Vegetation.isFoliage()} checks a double plant by block id,
     * with no block registry. A headless test has no block registry. A grass tuft reads as solid ground here.
     */
    private static class PlantedWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int y = position.getBlockY();
            if (y > SURFACE_Y + 1) return new BaseBlock(BlockID.AIR);
            if (y == SURFACE_Y + 1) return new BaseBlock(BlockID.DOUBLE_PLANT);
            if (y == SURFACE_Y) return new BaseBlock(BlockID.GRASS);
            if (y >= SURFACE_Y - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }

    /**
     * A stone ledge 2 blocks thick at {@link #LEDGE_Y} for x below 0, with air under the ledge, beside a valley at
     * {@link #VALLEY_Y}. A sphere on a steep hillside leaves this shape where the sphere hangs over the drop.
     */
    private static class LedgeWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int y = position.getBlockY();

            if (position.getBlockX() < 0) {
                final boolean ledge = y == LEDGE_Y || y == LEDGE_Y - 1;
                return new BaseBlock(ledge ? BlockID.STONE : BlockID.AIR);
            }

            if (y > VALLEY_Y) return new BaseBlock(BlockID.AIR);
            if (y == VALLEY_Y) return new BaseBlock(BlockID.GRASS);
            if (y >= VALLEY_Y - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }
}
