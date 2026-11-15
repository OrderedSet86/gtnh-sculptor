package com.gtnh.sculptor.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.gtnh.sculptor.brush.BrushParams;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * The preview has the blocks that the commit writes: a brush run through a preview context and through a commit context
 * over a real {@link EditSession} writes the same blocks at the same positions.
 */
public class PreviewMatchesCommitTest {

    /**
     * Every brush except {@link SculptorBrush#TREE}. A tree generator uses the biome and block registries of Minecraft.
     * A unit test has no registries, so the tree brush writes nothing here.
     */
    @Test
    public void everyBrushPreviewsWhatItCommits() throws Exception {
        for (SculptorBrush brush : SculptorBrush.values()) {
            if (brush == SculptorBrush.TREE) continue;
            assertPreviewMatchesCommit(brush);
        }
    }

    @Test
    public void previewDoesNotTouchTheWorld() throws Exception {
        Map<BlockVector, BaseBlock> written = new LinkedHashMap<>();
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld(written));

        SculptorBrush.SPHERE.apply(preview, new Vector(0, 63, 0), createParams(3));

        assertTrue("preview must not write to the world", written.isEmpty());
        assertFalse(
            "preview must capture something",
            preview.getPendingChanges()
                .isEmpty());
    }

    @Test
    public void previewReadsSeeItsOwnWrites() throws Exception {
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld(new LinkedHashMap<>()));
        Vector pos = new Vector(10, 100, 10);

        assertTrue(preview.isAir(pos));
        preview.setBlock(pos, new BaseBlock(BlockID.STONE));

        assertEquals(
            BlockID.STONE,
            preview.getBlock(pos)
                .getType());
        assertFalse(preview.isAir(pos));
    }

    @Test
    public void airOnlyMaskLeavesExistingBlocksAlone() throws Exception {
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld(new LinkedHashMap<>()));
        preview.setPlacementMask(PlacementMask.AIR_ONLY);

        // column (0, 0): stone up to y=60, a plant at y=61, and air above
        assertFalse(preview.setBlock(new Vector(0, 60, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 61, 0), new BaseBlock(BlockID.DIRT)));
        assertTrue(preview.setBlock(new Vector(0, 62, 0), new BaseBlock(BlockID.DIRT)));

        assertEquals(
            1,
            preview.getPendingChanges()
                .size());
    }

    @Test
    public void replaceBlockMaskOnlyOverwritesMatchingBlocks() throws Exception {
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld(new LinkedHashMap<>()));
        preview.setPlacementMask(PlacementMask.REPLACE_BLOCK);
        preview.setReplaceBlock(new BaseBlock(BlockID.STONE));

        // the stone matches the replace block. The plant and the air above the stone do not
        assertTrue(preview.setBlock(new Vector(0, 60, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 61, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 62, 0), new BaseBlock(BlockID.DIRT)));
    }

    private void assertPreviewMatchesCommit(SculptorBrush brush) throws Exception {
        Vector centre = new Vector(0, 63, 0);
        BrushParams params = createParams(4);

        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld(new LinkedHashMap<>()));
        brush.apply(preview, centre, params);

        Map<BlockVector, BaseBlock> written = new LinkedHashMap<>();
        EditSession session = createSession(new TestWorld(written));
        SculptorEditContext commit = SculptorEditContext.commit(session);
        brush.apply(commit, centre, params);
        session.flushQueue();

        Map<BlockVector, BaseBlock> pending = preview.getPendingChanges();

        // a brush that changes nothing on both sides would pass the comparison
        assertFalse(brush + ": produced no changes, test would be vacuous", pending.isEmpty());
        assertEquals(brush + ": same positions touched", written.keySet(), pending.keySet());
        for (Map.Entry<BlockVector, BaseBlock> entry : written.entrySet()) {
            BaseBlock previewed = pending.get(entry.getKey());
            assertEquals(
                brush + ": block type at " + entry.getKey(),
                entry.getValue()
                    .getType(),
                previewed.getType());
            assertEquals(
                brush + ": block data at " + entry.getKey(),
                entry.getValue()
                    .getData(),
                previewed.getData());
        }
    }

    // an EditSession of WorldEdit, with the full extent chain, over the test world, with no block limit
    private static EditSession createSession(TestWorld world) {
        return WorldEdit.getInstance()
            .getEditSessionFactory()
            .getEditSession(world, -1);
    }

    private static BrushParams createParams(int radius) {
        return BrushParams.builder()
            .radius(radius)
            .material(new BaseBlock(BlockID.STONE))
            .secondary(3)
            .strength(0.6D)
            .autoSmooth(0.0D)
            .useSelectedMaterial(false)
            .build();
    }

    /**
     * Stone terrain with a surface from y=60 to y=65, and double plants on a third of the columns. On flat ground no
     * brush reads a position that the same stroke wrote, and the test passes with read-your-writes broken. Each write
     * also goes into {@code written}.
     */
    private static class TestWorld extends NullWorld {

        private final Map<BlockVector, BaseBlock> overrides = new LinkedHashMap<>();
        private final Map<BlockVector, BaseBlock> written;

        TestWorld(Map<BlockVector, BaseBlock> written) {
            this.written = written;
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            BaseBlock override = overrides.get(position.toBlockVector());
            if (override != null) return new BaseBlock(override);

            int x = position.getBlockX();
            int y = position.getBlockY();
            int z = position.getBlockZ();
            int surface = computeSurfaceY(x, z);

            if (y <= surface) return new BaseBlock(BlockID.STONE);

            // plants on the surface, for the foliage brush. Vegetation.isFoliage() checks DOUBLE_PLANT by id, with no
            // block registry. A headless test has no block registry
            if (y == surface + 1 && Math.floorMod(x * 5 + z * 11, 3) == 0) {
                return new BaseBlock(BlockID.DOUBLE_PLANT);
            }
            return new BaseBlock(BlockID.AIR);
        }

        private static int computeSurfaceY(int x, int z) {
            return 60 + Math.floorMod(x * 7 + z * 3, 6);
        }

        @Override
        public boolean setBlock(Vector position, BaseBlock block, boolean notifyAndLight) {
            overrides.put(position.toBlockVector(), new BaseBlock(block));
            written.put(position.toBlockVector(), new BaseBlock(block));
            return true;
        }
    }
}
