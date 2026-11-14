package com.gtnh.sculptor.brush;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * The limits of the auto-smooth pass after a placement brush, on a rough hillside.
 *
 * <p>
 * The pass may cut the material of the stroke down to the ground with no limit: a cylinder 19 blocks above a hillside
 * tapers down to the ground. A cut into the hillside, or a raise, has a limit of {@code LEASH} blocks. With no limit,
 * the mean that tapers the shape also hollows the slope beside the shape and raises pillars in the valley. With both
 * limited, the placement stays a flat-topped mesa.
 * </p>
 */
public class AutoSmoothTest {

    private static final int RADIUS = 12;
    private static final int HEIGHT = 19;
    private static final int CENTRE_Y = 85;

    // the limit in SculptorBrush.apply(): 2 blocks, or a third of the radius
    private static final int LEASH = Math.max(2, Math.round(RADIUS / 3.0F));

    /**
     * Compare with the same stroke with the pass off, so the comparison is of the pass and not of the cylinder. Against
     * the world before the stroke, the difference is mostly the placement and not the pass.
     */
    @Test
    public void autoSmoothTapersThePlacementWithoutReshapingTheHillside() throws Exception {
        final SculptorEditContext plain = runStroke(0.0D);
        final SculptorEditContext smoothed = runStroke(1.0D);

        final int span = RADIUS + Math.max(3, RADIUS / 2) + 2;

        int taper = 0;
        int dugBelowGround = 0;
        int raisedAboveGround = 0;
        int floating = 0;

        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                final int before = findSurfaceY(plain, dx, dz);
                final int after = findSurfaceY(smoothed, dx, dz);
                if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) continue;

                final int ground = SlopeWorld.computeTopY(dx, dz);

                taper = Math.max(taper, before - after);
                dugBelowGround = Math.max(dugBelowGround, ground - after);
                raisedAboveGround = Math.max(raisedAboveGround, after - Math.max(before, ground));

                floating += (measureGapUnder(smoothed, dx, dz) > 0) ? 1 : 0;
            }
        }

        assertTrue(
            "the pass must be free to cut the placement back to the ground. The largest cut was only " + taper,
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
     * A cylinder fills up from the target height across the disc, so on a slope the downhill half has nothing under the
     * cylinder. The smoothing pass is a height map, with no data under a shape, so the shape fills down to the ground
     * before the pass. With auto-smooth at 0, the base of the cylinder stays in the air, for building in the air.
     */
    @Test
    public void shapeOnASlopeSettlesOntoTheGroundOnlyWhenAutoSmoothIsOn() throws Exception {
        final int span = RADIUS + Math.max(3, RADIUS / 2) + 2;

        final int hangingWithout = countColumnsLeftHanging(runStroke(0.0D), span);
        final int hangingWith = countColumnsLeftHanging(runStroke(1.0D), span);

        assertTrue(
            "the test slope must leave the plain cylinder hanging, or the test proves nothing. Got " + hangingWithout,
            hangingWithout > 50);

        assertTrue("auto-smooth left " + hangingWith + " columns hanging over a gap", hangingWith == 0);
    }

    private static SculptorEditContext runStroke(double autoSmooth) throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(new SlopeWorld());
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            BrushParams.builder()
                .radius(RADIUS)
                .material(new BaseBlock(BlockID.STONE))
                .secondary(HEIGHT)
                .strength(1.0D)
                .autoSmooth(autoSmooth)
                .useSelectedMaterial(false)
                .build());
        return ctx;
    }

    private static int countColumnsLeftHanging(SculptorEditContext ctx, int span) {
        int hanging = 0;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                if (measureGapUnder(ctx, dx, dz) > 0) hanging++;
            }
        }
        return hanging;
    }

    /**
     * Measure the air gap under the highest solid run in a column: 0 when the run reaches the ground. A check of the
     * one block under the surface is not enough. A slab of 19 blocks over a hillside has a solid block under each of
     * the top 18 blocks of the slab.
     */
    private static int measureGapUnder(SculptorEditContext ctx, int x, int z) {
        int y = findSurfaceY(ctx, x, z);
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

    private static int findSurfaceY(SculptorEditContext ctx, int x, int z) {
        for (int y = 220; y >= 0; y--) {
            if (!ctx.isAir(new Vector(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** A hillside that falls one block per block of x, with up to 3 blocks of roughness. */
    private static class SlopeWorld extends NullWorld {

        static int computeTopY(int x, int z) {
            return CENTRE_Y - x + (Math.floorMod(computeHash(x, z), 7) - 3);
        }

        private static int computeHash(int x, int z) {
            int h = x * 374761393 + z * 668265263;
            h = (h ^ (h >> 13)) * 1274126177;
            return h ^ (h >> 16);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int top = computeTopY(position.getBlockX(), position.getBlockZ());
            final int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }
}
