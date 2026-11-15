package com.gtnh.sculptor.brush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The cylinder brush on {@link RecordedTerrain} from a saved world.
 */
public class RealTerrainTest {

    private static final int RADIUS = 6;
    private static final int HEIGHT = 8;

    /**
     * The cantilever removes no block of the terrain. On a rough cliff face, a blend that cuts each bump within reach
     * by a block or two would remove over a hundred blocks in one stroke, away from the shape.
     */
    @Test
    public void cantileverNeverDeletesTerrainThatWasAlreadyThere() throws Exception {
        final String[] places = { "noBottomSmoothing", "strayRemovals" };
        final int[][] centres = { { 907, 40, -623 }, { 904, 43, -623 } };

        for (int i = 0; i < places.length; i++) {
            final RecordedTerrain world = RecordedTerrain.named(places[i]);
            final Vector at = new Vector(centres[i][0], centres[i][1], centres[i][2]);

            final SculptorEditContext ctx = runCylinder(world, at, createParams(HEIGHT, 1.0D, OverhangMode.CANTILEVER));

            assertTrue(
                "nothing was placed at " + places[i] + ", so this proves nothing",
                ctx.getPendingChanges()
                    .size() > 100);
            assertEquals("cantilever deleted terrain at " + places[i], 0, countTerrainBlocksDeleted(world, ctx));
        }
    }

    /**
     * A cylinder cut into a rock face gets a curved underside in at least 6 of the 13 columns on the X axis. A profile
     * read from the world after the placement would be the run of the cliff, and most columns would get no underside.
     *
     * <p>
     * The rock here stands 8 to 12 blocks above the brush centre, so the cylinder is 12 blocks tall. A shorter cylinder
     * is inside the rock and has no underside.
     * </p>
     */
    @Test
    public void cylinderCutIntoARockFaceGetsAnUnderside() throws Exception {
        final Vector at = new Vector(907, 40, -623);
        final int tall = 12;

        final SculptorEditContext plain = runCylinder(
            RecordedTerrain.named("noBottomSmoothing"),
            at,
            createParams(tall, 0.0D, OverhangMode.CANTILEVER));
        final SculptorEditContext shaped = runCylinder(
            RecordedTerrain.named("noBottomSmoothing"),
            at,
            createParams(tall, 1.0D, OverhangMode.CANTILEVER));

        int curved = 0;
        int deepest = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            final Vector column = at.add(dx, 0, 0);
            final int before = findUndersideY(plain, column);
            final int after = findUndersideY(shaped, column);
            if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) continue;

            if (before > after) curved++;
            deepest = Math.max(deepest, before - after);
        }

        assertTrue("only " + curved + " columns got an underside, out of " + (2 * RADIUS + 1), curved >= 6);
        assertTrue("the underside dropped " + deepest + " blocks under a " + tall + "-block shape", deepest <= tall);
    }

    /**
     * With {@link OverhangMode#SETTLE} the shape becomes part of the hill, so the pass may cut the ground around the
     * shape.
     */
    @Test
    public void settleIsStillAllowedToReshapeTheGround() throws Exception {
        final RecordedTerrain world = RecordedTerrain.named("strayRemovals");
        final Vector at = new Vector(904, 43, -623);

        final SculptorEditContext ctx = runCylinder(world, at, createParams(HEIGHT, 1.0D, OverhangMode.SETTLE));
        assertTrue(
            "fill-to-ground should still be able to cut into terrain",
            countTerrainBlocksDeleted(world, ctx) > 0);
    }

    /**
     * An air stroke carves, and has no underside, so the overhang mode has no effect on the blocks of the stroke. The
     * strokes are on a grid of centres around each recorded cliff, in the cliff and in the air beside the cliff.
     */
    @Test
    public void airStrokeIgnoresTheOverhangMode() throws Exception {
        final String[] places = { "noBottomSmoothing", "strayRemovals" };
        final int[][] centres = { { 907, 40, -623 }, { 904, 43, -623 } };

        for (int i = 0; i < places.length; i++) {
            for (int dx = -6; dx <= 6; dx += 3) {
                for (int dy = -4; dy <= 6; dy += 2) {
                    final Vector at = new Vector(centres[i][0] + dx, centres[i][1] + dy, centres[i][2]);
                    for (SculptorBrush brush : new SculptorBrush[] { SculptorBrush.SPHERE, SculptorBrush.CYLINDER }) {
                        final Map<BlockVector, BaseBlock> settled = runAirStroke(
                            places[i],
                            brush,
                            at,
                            OverhangMode.SETTLE);
                        final Map<BlockVector, BaseBlock> cantilevered = runAirStroke(
                            places[i],
                            brush,
                            at,
                            OverhangMode.CANTILEVER);
                        assertEquals(places[i] + " " + brush + " at " + at, settled, cantilevered);
                    }
                }
            }
        }
    }

    private static Map<BlockVector, BaseBlock> runAirStroke(String place, SculptorBrush brush, Vector at,
        OverhangMode mode) throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(RecordedTerrain.named(place));
        final BrushParams params = createParams(HEIGHT, 0.8D, mode).toBuilder()
            .material(new BaseBlock(BlockID.AIR))
            .build();
        brush.apply(ctx, at, params);
        return ctx.getPendingChanges();
    }

    private static SculptorEditContext runCylinder(RecordedTerrain world, Vector at, BrushParams params)
        throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(world);
        SculptorBrush.CYLINDER.apply(ctx, at, params);
        return ctx;
    }

    private static BrushParams createParams(int height, double autoSmooth, OverhangMode mode) {
        return BrushParams.builder()
            .radius(RADIUS)
            .material(new BaseBlock(BlockID.STONE))
            .secondary(height)
            .strength(1.0D)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(false)
            .overhang(mode)
            .build();
    }

    private static int countTerrainBlocksDeleted(RecordedTerrain world, SculptorEditContext ctx) {
        int deleted = 0;
        for (Map.Entry<BlockVector, BaseBlock> change : ctx.getPendingChanges()
            .entrySet()) {
            if (change.getValue()
                .getType() != BlockID.AIR) continue;

            final BlockVector at = change.getKey();
            if (world.isSolid(at.getBlockX(), at.getBlockY(), at.getBlockZ())) deleted++;
        }
        return deleted;
    }

    // the bottom of the top solid run of the column, from 40 blocks above the centre
    private static int findUndersideY(SculptorEditContext ctx, Vector centreColumn) {
        final int x = centreColumn.getBlockX();
        final int z = centreColumn.getBlockZ();
        int y = centreColumn.getBlockY() + 40;
        while (y >= 0 && ctx.isAir(new Vector(x, y, z))) y--;
        if (y < 0) return Integer.MIN_VALUE;
        while (y > 0 && !ctx.isAir(new Vector(x, y - 1, z))) y--;
        return y;
    }
}
