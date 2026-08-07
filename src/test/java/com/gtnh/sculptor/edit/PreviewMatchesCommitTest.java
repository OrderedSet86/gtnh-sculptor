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

package com.gtnh.sculptor.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.gtnh.sculptor.brush.BrushParams;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * The guarantee the whole editor rests on: what the ghost shows is what committing does.
 *
 * <p>
 * A brush never learns whether it is previewing. Both modes run the identical call through a
 * {@link SculptorEditContext} backed differently, so this checks that the two backings agree --
 * and in particular that reads see pending writes in preview mode, since terrain brushes read
 * back positions they have just written.
 * </p>
 */
public class PreviewMatchesCommitTest {

    /**
     * Uneven stone terrain around y=60-65.
     *
     * <p>
     * Deliberately not flat. On flat ground a brush never reads back a position it has just
     * written -- every column resolves identically and independently -- so the equivalence check
     * would pass even with read-your-writes broken, which is exactly the bug it exists to catch.
     * Varying the surface makes columns interact through erosion, smoothing and neighbour
     * counts.
     * </p>
     */
    private static class TestWorld extends NullWorld {

        final Map<BlockVector, BaseBlock> overrides = new LinkedHashMap<BlockVector, BaseBlock>();

        private static int surfaceAt(int x, int z) {
            // Cheap deterministic bumpiness; no randomness, so runs are reproducible.
            return 60 + Math.floorMod(x * 7 + z * 3, 6);
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            BaseBlock override = overrides.get(position.toBlockVector());
            if (override != null) return new BaseBlock(override);

            int x = position.getBlockX();
            int y = position.getBlockY();
            int z = position.getBlockZ();
            int surface = surfaceAt(x, z);

            if (y <= surface) return new BaseBlock(BlockID.STONE);

            // Scatter plants one above the surface so the foliage brush has something to do.
            // DOUBLE_PLANT specifically: it is recognised by explicit id rather than by material,
            // so it does not need Minecraft's block registry, which is not bootstrapped in a
            // headless test.
            if (y == surface + 1 && Math.floorMod(x * 5 + z * 11, 3) == 0) {
                return new BaseBlock(BlockID.DOUBLE_PLANT);
            }
            return new BaseBlock(BlockID.AIR);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }

        @Override
        public boolean setBlock(Vector position, BaseBlock block, boolean notifyAndLight) {
            overrides.put(position.toBlockVector(), new BaseBlock(block));
            return true;
        }
    }

    /**
     * A stand-in for a committing EditSession: writes land in a world, reads come back from it.
     * Enough to exercise the commit branch without booting a WorldEdit platform.
     */
    private static EditSession committingSessionOver(final TestWorld world, final Map<BlockVector, BaseBlock> written)
        throws Exception {
        EditSession session = mock(EditSession.class);

        when(session.getWorld()).thenReturn(world);
        when(session.getBlock(any(Vector.class))).thenAnswer(new Answer<BaseBlock>() {

            @Override
            public BaseBlock answer(InvocationOnMock invocation) {
                return world.getBlock((Vector) invocation.getArgument(0));
            }
        });
        doAnswer(new Answer<Boolean>() {

            @Override
            public Boolean answer(InvocationOnMock invocation) {
                Vector position = invocation.getArgument(0);
                BaseBlock block = invocation.getArgument(1);
                written.put(position.toBlockVector(), new BaseBlock(block));
                // Writing through means later reads in the same operation see it, which is what
                // a real committing session does.
                world.setBlock(position, block, false);
                return Boolean.TRUE;
            }
        }).when(session)
            .setBlock(any(Vector.class), any(BaseBlock.class));

        return session;
    }

    private static BrushParams params(int radius) {
        return new BrushParams(radius, new BaseBlock(BlockID.STONE), 3, 0.6D, 0.0D, false);
    }

    private void assertPreviewMatchesCommit(SculptorBrush brush) throws Exception {
        Vector centre = new Vector(0, 63, 0);
        BrushParams params = params(4);

        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld());
        brush.apply(preview, centre, params);

        TestWorld commitWorld = new TestWorld();
        Map<BlockVector, BaseBlock> written = new LinkedHashMap<BlockVector, BaseBlock>();
        SculptorEditContext commit = SculptorEditContext.commit(committingSessionOver(commitWorld, written));
        brush.apply(commit, centre, params);

        Map<BlockVector, BaseBlock> pending = preview.getPendingChanges();

        // Without this an inert brush would satisfy the comparison below by changing nothing on
        // both sides, and the check would silently mean nothing.
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

    /**
     * Every brush that can run headlessly, not a sample. The read-back brushes are the ones at
     * risk, but a shape brush regressing would be just as wrong and is free to check.
     *
     * <p>
     * {@link SculptorBrush#TREE} is excluded because vanilla tree generation needs Minecraft's
     * biome and block registries, which are not bootstrapped in a unit test, so it produces
     * nothing here. That leaves its preview-vs-commit equivalence resting on the position-derived
     * seeding rather than on this test -- worth knowing, since an unseeded generator would grow
     * a different tree on commit than the ghost showed.
     * </p>
     */
    @Test
    public void everyBrushPreviewsExactlyWhatItCommits() throws Exception {
        for (SculptorBrush brush : SculptorBrush.values()) {
            if (brush == SculptorBrush.TREE) continue;
            assertPreviewMatchesCommit(brush);
        }
    }

    @Test
    public void previewDoesNotTouchTheWorld() throws Exception {
        TestWorld world = new TestWorld();
        SculptorEditContext preview = SculptorEditContext.preview(world);

        SculptorBrush.SPHERE.apply(preview, new Vector(0, 63, 0), params(3));

        assertTrue("preview must not write to the world", world.overrides.isEmpty());
        assertFalse(
            "preview must capture something",
            preview.getPendingChanges()
                .isEmpty());
    }

    /**
     * The property that makes read-back brushes previewable at all.
     */
    @Test
    public void previewReadsSeeItsOwnWrites() throws Exception {
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld());
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
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld());
        preview.setPlacementMask(PlacementMask.AIR_ONLY);

        // Column (0,0): stone to y=60, a plant at y=61, open air above.
        assertFalse(preview.setBlock(new Vector(0, 60, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 61, 0), new BaseBlock(BlockID.DIRT)));
        assertTrue(preview.setBlock(new Vector(0, 62, 0), new BaseBlock(BlockID.DIRT)));

        assertEquals(
            1,
            preview.getPendingChanges()
                .size());
    }

    @Test
    public void replaceMaterialMaskOnlyOverwritesMatchingBlocks() throws Exception {
        SculptorEditContext preview = SculptorEditContext.preview(new TestWorld());
        preview.setPlacementMask(PlacementMask.REPLACE_MATERIAL);
        preview.setMaterial(new BaseBlock(BlockID.STONE));

        // Stone matches the brush material; the plant above it and the air above that do not.
        assertTrue(preview.setBlock(new Vector(0, 60, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 61, 0), new BaseBlock(BlockID.DIRT)));
        assertFalse(preview.setBlock(new Vector(0, 62, 0), new BaseBlock(BlockID.DIRT)));
    }

}
