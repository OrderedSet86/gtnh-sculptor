package com.gtnh.sculptor.brush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Random;

import org.junit.Test;

import com.gtnh.sculptor.edit.PlacementMask;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * The rule of {@link PlacementMask#REPLACE_BLOCK}: a stroke changes a position only where the world had the replace
 * block before the stroke, with the same data value. The terrain, the brush, the radius and the replace block come from
 * a seeded {@link Random}. The boundary cases run for each brush: radius 1 and {@link #MAX_RADIUS}, with a replace
 * block that differs from a block of the terrain by the data value only.
 */
public class ReplaceBlockMaskTest {

    private static final int CASES = 300;
    private static final int MAX_RADIUS = 5;
    private static final int GROUND_Y = 63;
    // two wool colours, so with one colour as the replace block the test checks that the other colour stays unchanged
    private static final BaseBlock[] PALETTE = { new BaseBlock(BlockID.STONE), new BaseBlock(BlockID.DIRT),
        new BaseBlock(BlockID.GRASS), new BaseBlock(BlockID.SAND), new BaseBlock(BlockID.CLOTH, 3),
        new BaseBlock(BlockID.CLOTH, 5) };
    private static final SculptorBrush[] BRUSHES = { SculptorBrush.SPHERE, SculptorBrush.CYLINDER, SculptorBrush.RAISE,
        SculptorBrush.LOWER, SculptorBrush.SMOOTH, SculptorBrush.ERODE };

    @Test
    public void aStrokeChangesOnlyTheReplaceBlock() throws Exception {
        final Random random = new Random(31L);
        int changedTotal = 0;
        for (int run = 0; run < CASES; run++) {
            final MixedWorld world = new MixedWorld(random.nextLong());
            final SculptorBrush brush = BRUSHES[random.nextInt(BRUSHES.length)];
            final BaseBlock material = random.nextBoolean() ? new BaseBlock(BlockID.AIR)
                : PALETTE[random.nextInt(PALETTE.length)];
            final BrushParams params = BrushParams.builder()
                .radius(1 + random.nextInt(MAX_RADIUS))
                .material(material)
                .strength(1.0D)
                .autoSmooth(random.nextBoolean() ? 0.0D : 0.5D)
                .useSelectedMaterial(random.nextBoolean())
                .replaceBlock(PALETTE[random.nextInt(PALETTE.length)])
                .build();
            changedTotal += checkOnlyReplaceBlockChanges(world, brush, params);
        }
        assertTrue("no stroke changed a block", changedTotal > 0);

        final MixedWorld world = new MixedWorld(41L);
        for (final SculptorBrush brush : BRUSHES) {
            for (final int radius : new int[] { 1, MAX_RADIUS }) {
                final BrushParams params = BrushParams.builder()
                    .radius(radius)
                    .strength(1.0D)
                    .replaceBlock(new BaseBlock(BlockID.CLOTH, 3))
                    .build();
                checkOnlyReplaceBlockChanges(world, brush, params);
                checkOnlyReplaceBlockChanges(
                    world,
                    brush,
                    params.toBuilder()
                        .material(new BaseBlock(BlockID.AIR))
                        .build());
            }
        }
    }

    @Test
    public void anAirSphereRemovesOnlyTheReplaceBlock() throws Exception {
        final Random random = new Random(37L);
        int removedTotal = 0;
        for (int run = 0; run < CASES; run++) {
            final MixedWorld world = new MixedWorld(random.nextLong());
            final BaseBlock replaceBlock = PALETTE[random.nextInt(PALETTE.length)];
            final BrushParams params = BrushParams.builder()
                .radius(1 + random.nextInt(MAX_RADIUS))
                .material(new BaseBlock(BlockID.AIR))
                .replaceBlock(replaceBlock)
                .build();
            final Map<BlockVector, BaseBlock> changes = runStroke(world, SculptorBrush.SPHERE, params);
            for (final Map.Entry<BlockVector, BaseBlock> change : changes.entrySet()) {
                assertEquals(
                    "an air sphere wrote a block at " + change.getKey(),
                    BlockID.AIR,
                    change.getValue()
                        .getType());
                assertSameBlock(change.getKey(), replaceBlock, world.getBlock(change.getKey()));
            }
            removedTotal += changes.size();
        }
        assertTrue("no air sphere removed a block", removedTotal > 0);
    }

    // the count of changed positions
    private static int checkOnlyReplaceBlockChanges(MixedWorld world, SculptorBrush brush, BrushParams params)
        throws Exception {
        final Map<BlockVector, BaseBlock> changes = runStroke(world, brush, params);
        for (final BlockVector position : changes.keySet()) {
            assertSameBlock(position, params.replaceBlock(), world.getBlock(position));
        }
        return changes.size();
    }

    private static Map<BlockVector, BaseBlock> runStroke(MixedWorld world, SculptorBrush brush, BrushParams params)
        throws Exception {
        final SculptorEditContext ctx = SculptorEditContext.preview(world);
        ctx.setPlacementMask(PlacementMask.REPLACE_BLOCK);
        brush.apply(ctx, new Vector(0, GROUND_Y, 0), params);
        return ctx.getPendingChanges();
    }

    private static void assertSameBlock(BlockVector position, BaseBlock expected, BaseBlock before) {
        final String where = "changed " + before.getType() + ":" + before.getData() + " at " + position;
        assertEquals(where, expected.getType(), before.getType());
        assertEquals(where, expected.getData(), before.getData());
    }

    /**
     * Columns of random height around {@link #GROUND_Y}, each block from {@link #PALETTE} by a hash of the position.
     */
    private static final class MixedWorld extends NullWorld {

        private final long seed;

        private MixedWorld(long seed) {
            this.seed = seed;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            final int x = position.getBlockX();
            final int z = position.getBlockZ();
            final int height = GROUND_Y - 3 + new Random(seed ^ (x * 73_856_093L) ^ (z * 83_492_791L)).nextInt(7);
            if (position.getBlockY() > height) return new BaseBlock(BlockID.AIR);

            final long cell = seed ^ (x * 73_856_093L) ^ (position.getBlockY() * 19_349_663L) ^ (z * 83_492_791L);
            final BaseBlock block = PALETTE[new Random(cell).nextInt(PALETTE.length)];
            return new BaseBlock(block.getType(), block.getData());
        }
    }
}
