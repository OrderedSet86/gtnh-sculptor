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
 * When a placed shape fills down to the terrain under the shape, with {@link OverhangMode#SETTLE}.
 *
 * <p>
 * A shape that rests on the terrain in one column fills down. A shape built above the terrain does not. The distance to
 * the ground is not the test: a limit of twice the radius would leave a large brush floating over a deep drop where a
 * small brush settles.
 * </p>
 */
public class SettleOntoGroundTest {

    private static final int CENTRE_Y = 120;
    private static final int HEIGHT = 10;

    /**
     * Each radius from 8 to 20 over each drop from 10 to 60 blocks. The drops of 24, 32 and 40 blocks are twice the
     * radii 12, 16 and 20.
     */
    @Test
    public void shapeOnACliffEdgeFollowsTheDropHoweverDeepItIs() throws Exception {
        for (int radius : new int[] { 8, 12, 16, 20 }) {
            for (int drop : new int[] { 10, 24, 32, 40, 60 }) {
                final int hanging = countColumnsLeftHanging(runCylinder(new CliffWorld(drop), radius, 1.0D), radius);
                assertEquals("radius " + radius + " over a " + drop + "-block drop left columns hanging", 0, hanging);
            }
        }
    }

    /** No column of the footprint touches the terrain, so the shape stays as drawn. */
    @Test
    public void shapeBuiltInOpenSkyIsNotDraggedDownToTheGround() throws Exception {
        final int radius = 12;
        final int hanging = countColumnsLeftHanging(runCylinder(new DistantGroundWorld(), radius, 1.0D), radius);

        assertTrue("a cylinder in open sky should still be standing in open sky, got " + hanging, hanging > 100);
    }

    @Test
    public void withAutoSmoothOffTheShapeKeepsTheGeometryItWasDrawnWith() throws Exception {
        final int radius = 12;
        final int hanging = countColumnsLeftHanging(runCylinder(new CliffWorld(40), radius, 0.0D), radius);

        assertTrue("with the slider at zero a cylinder is a cylinder, got " + hanging, hanging > 100);
    }

    private static SculptorEditContext runCylinder(NullWorld world, int radius, double autoSmooth) throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(world);
        SculptorBrush.CYLINDER.apply(
            ctx,
            new Vector(0, CENTRE_Y, 0),
            BrushParams.builder()
                .radius(radius)
                .material(new BaseBlock(BlockID.STONE))
                .secondary(HEIGHT)
                .strength(1.0D)
                .autoSmooth(autoSmooth)
                .useSelectedMaterial(false)
                .build());
        return ctx;
    }

    /** Count the columns with air under the highest solid run, and a block below the air. */
    private static int countColumnsLeftHanging(SculptorEditContext ctx, int radius) {
        int hanging = 0;
        for (int dx = -radius - 2; dx <= radius + 2; dx++) {
            for (int dz = -radius - 2; dz <= radius + 2; dz++) {
                int y = 250;
                while (y >= 0 && ctx.isAir(new Vector(dx, y, dz))) y--;
                if (y < 0) continue;
                while (y >= 0 && !ctx.isAir(new Vector(dx, y, dz))) y--;
                if (y < 0) continue;

                boolean gap = false;
                while (y >= 0 && ctx.isAir(new Vector(dx, y, dz))) {
                    gap = true;
                    y--;
                }
                if (gap && y >= 0) hanging++;
            }
        }
        return hanging;
    }

    /** Ground at {@link #CENTRE_Y} for x up to 0, and {@code drop} blocks lower for x above 0. */
    private static class CliffWorld extends NullWorld {

        private final int drop;

        CliffWorld(int drop) {
            this.drop = drop;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int top = (position.getBlockX() <= 0) ? CENTRE_Y : CENTRE_Y - drop;
            final int y = position.getBlockY();
            if (y > top) return new BaseBlock(BlockID.AIR);
            if (y == top) return new BaseBlock(BlockID.GRASS);
            if (y >= top - 3) return new BaseBlock(BlockID.DIRT);
            return new BaseBlock(BlockID.STONE);
        }
    }

    /** Flat ground 60 blocks below the brush, so no column of the shape touches the ground. */
    private static class DistantGroundWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int y = position.getBlockY();
            if (y > CENTRE_Y - 60) return new BaseBlock(BlockID.AIR);
            if (y == CENTRE_Y - 60) return new BaseBlock(BlockID.GRASS);
            return new BaseBlock(BlockID.STONE);
        }
    }
}
