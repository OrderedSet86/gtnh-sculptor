/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
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

package com.sk89q.worldedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.patterns.SingleBlockPattern;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.world.NullWorld;

/**
 * Tests that a preview {@link EditSession} captures exactly the changes a normal
 * session would apply, without touching the world.
 *
 * <p>
 * This is the foundation of the interactive editor: a brush is run against a preview
 * session to obtain the pending changes, those are rendered as a ghost overlay, and the
 * same operation is replayed against a normal session on commit. If the two ever
 * disagree, the preview lies.
 * </p>
 */
public class EditSessionPreviewTest {

    private static final Pattern STONE = new SingleBlockPattern(new BaseBlock(BlockID.STONE));

    /**
     * A world that actually stores what is written to it, so we can tell the difference
     * between "applied" and "captured".
     */
    private static class MapWorld extends NullWorld {

        private final Map<BlockVector, BaseBlock> blocks = new LinkedHashMap<BlockVector, BaseBlock>();

        @Override
        public boolean setBlock(Vector position, BaseBlock block, boolean notifyAndLight) {
            blocks.put(position.toBlockVector(), new BaseBlock(block));
            return true;
        }

        @Override
        public BaseBlock getBlock(Vector position) {
            BaseBlock block = blocks.get(position.toBlockVector());
            return (block != null) ? new BaseBlock(block) : new BaseBlock(BlockID.AIR);
        }

        @Override
        public BaseBlock getLazyBlock(Vector position) {
            return getBlock(position);
        }
    }

    private static EditSession session(MapWorld world, boolean preview) {
        return new EditSession(new EventBus(), world, -1, null, new EditSessionEvent(world, null, -1, null), preview);
    }

    @Test
    public void previewSessionDoesNotModifyTheWorld() throws Exception {
        MapWorld world = new MapWorld();
        EditSession preview = session(world, true);

        preview.makeSphere(new Vector(0, 64, 0), STONE, 3, true);

        assertTrue(preview.isPreview());
        assertTrue("preview must not write to the world", world.blocks.isEmpty());
        assertFalse(
            "preview must capture something",
            preview.getPendingChanges()
                .isEmpty());
    }

    @Test
    public void normalSessionReportsNoPendingChanges() throws Exception {
        MapWorld world = new MapWorld();
        EditSession normal = session(world, false);

        normal.makeSphere(new Vector(0, 64, 0), STONE, 3, true);

        assertFalse(normal.isPreview());
        assertTrue(
            normal.getPendingChanges()
                .isEmpty());
        assertFalse(world.blocks.isEmpty());
    }

    /**
     * The core guarantee: what the preview shows is what the commit does.
     */
    @Test
    public void previewCapturesExactlyWhatACommitApplies() throws Exception {
        MapWorld previewWorld = new MapWorld();
        MapWorld commitWorld = new MapWorld();

        EditSession preview = session(previewWorld, true);
        EditSession commit = session(commitWorld, false);

        preview.makeSphere(new Vector(0, 64, 0), STONE, 4, true);
        commit.makeSphere(new Vector(0, 64, 0), STONE, 4, true);

        Map<BlockVector, BaseBlock> pending = preview.getPendingChanges();

        assertEquals("same positions affected", commitWorld.blocks.keySet(), pending.keySet());

        for (Map.Entry<BlockVector, BaseBlock> entry : commitWorld.blocks.entrySet()) {
            BaseBlock previewed = pending.get(entry.getKey());
            assertEquals(
                "block type at " + entry.getKey(),
                entry.getValue()
                    .getType(),
                previewed.getType());
            assertEquals(
                "block data at " + entry.getKey(),
                entry.getValue()
                    .getData(),
                previewed.getData());
        }
    }

    /**
     * Regression test: {@link EditSession#getBlock(Vector)} normally bypasses the extent
     * chain and reads the world directly. In preview mode it must read through the capture
     * buffer instead, otherwise any operation that reads back a position it just wrote --
     * gravity, height maps, flood fills -- would preview differently from how it commits.
     */
    @Test
    public void previewReadsSeeOwnWritesThroughTheEditSession() throws Exception {
        MapWorld world = new MapWorld();
        EditSession preview = session(world, true);
        Vector pos = new Vector(10, 64, 10);

        assertEquals(
            BlockID.AIR,
            preview.getBlock(pos)
                .getType());

        preview.setBlock(pos, new BaseBlock(BlockID.STONE));

        assertEquals(
            BlockID.STONE,
            preview.getBlock(pos)
                .getType());
        assertEquals(
            BlockID.STONE,
            preview.getLazyBlock(pos)
                .getType());
        assertTrue("still must not have touched the world", world.blocks.isEmpty());
    }

    @Test
    public void normalSessionReadsAreUnaffected() throws Exception {
        MapWorld world = new MapWorld();
        EditSession normal = session(world, false);
        Vector pos = new Vector(1, 64, 1);

        normal.setBlock(pos, new BaseBlock(BlockID.STONE));

        assertEquals(
            BlockID.STONE,
            normal.getBlock(pos)
                .getType());
    }

}
