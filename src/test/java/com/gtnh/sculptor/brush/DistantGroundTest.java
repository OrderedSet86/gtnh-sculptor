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
 * The reach of the cantilever blend, around a tall leg of stone.
 *
 * <p>
 * To a height map, a vertical face is a slope, so to the mean, the ground at the foot of a tower is a column or two
 * from the top of the tower. With no floor, the ground rises around the foot of a leg toward a shape 40 blocks above.
 * </p>
 */
public class DistantGroundTest {

    private static final int GROUND = 64;
    private static final int LEG_TOP = 110;
    private static final int RADIUS = 10;
    private static final int HEIGHT = 8;

    @Test
    public void groundFarBelowTheShapeIsLeftAlone() throws Exception {
        assertEquals(
            "ground at the foot of the leg rose to meet a shape at its top",
            0,
            countGroundColumnsMoved(LEG_TOP));
    }

    /**
     * The control: a floor that turns the blend off everywhere passes the test above, so a shape near the ground must
     * still blend. The shape is 4 blocks above the ground. A shape level with the ground passes with any floor at or
     * below the base of the shape.
     */
    @Test
    public void groundJustBelowTheShapeIsStillBlended() throws Exception {
        assertTrue(
            "a shape placed just above the ground should still blend into it",
            countGroundColumnsMoved(GROUND + 4) > 0);
    }

    /** Count the columns of the flat ground whose surface the auto-smooth pass moved. */
    private static int countGroundColumnsMoved(int brushY) throws Exception {
        final SculptorEditContext plain = SculptorEditContext.preview(new LegWorld());
        final SculptorEditContext shaped = SculptorEditContext.preview(new LegWorld());

        final Vector at = new Vector(0, brushY, 0);
        final BrushParams params = BrushParams.builder()
            .radius(RADIUS)
            .material(new BaseBlock(BlockID.STONE))
            .secondary(HEIGHT)
            .strength(1.0D)
            .autoSmooth(0.0D)
            .useSelectedMaterial(false)
            .overhang(OverhangMode.CANTILEVER)
            .build();
        SculptorBrush.CYLINDER.apply(plain, at, params);
        SculptorBrush.CYLINDER.apply(
            shaped,
            at,
            params.toBuilder()
                .autoSmooth(1.0D)
                .build());

        final int reach = RADIUS + Math.max(3, RADIUS / 2);
        int moved = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                final int before = findTopY(plain, dx, dz);
                // the flat ground only, not the leg
                if (before > GROUND + 2) continue;
                if (findTopY(shaped, dx, dz) != before) moved++;
            }
        }
        return moved;
    }

    private static int findTopY(SculptorEditContext ctx, int x, int z) {
        int y = 200;
        while (y >= 0 && ctx.isAir(new Vector(x, y, z))) y--;
        return y;
    }

    /** Flat stone ground at {@link #GROUND}, with a stone leg of radius 6 up to {@link #LEG_TOP}, as under an arch. */
    private static class LegWorld extends NullWorld {

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int x = position.getBlockX(), z = position.getBlockZ(), y = position.getBlockY();
            final boolean leg = x * x + z * z <= 36 && y <= LEG_TOP;
            return new BaseBlock((y <= GROUND || leg) ? BlockID.STONE : BlockID.AIR);
        }
    }
}
