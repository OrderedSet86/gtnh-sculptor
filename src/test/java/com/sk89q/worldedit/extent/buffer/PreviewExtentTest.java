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

package com.sk89q.worldedit.extent.buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.NullWorld;

/**
 * Tests for {@link PreviewExtent}, which captures block changes rather than
 * applying them so a pending edit can be rendered before it is committed.
 */
public class PreviewExtentTest {

    private PreviewExtent extent;

    @Before
    public void setUp() {
        // NullWorld reads back as air everywhere, so it stands in for "untouched world"
        extent = new PreviewExtent(NullWorld.getInstance());
    }

    @Test
    public void setBlockIsCapturedRatherThanApplied() throws WorldEditException {
        extent.setBlock(new Vector(1, 2, 3), new BaseBlock(BlockID.STONE));

        assertEquals(1, extent.size());
        assertEquals(
            BlockID.STONE,
            extent.getPendingChanges()
                .get(new Vector(1, 2, 3).toBlockVector())
                .getType());
    }

    /**
     * The behaviour that distinguishes this from {@link ForgetfulExtentBuffer}: a read
     * of a written position must see the pending value, not the delegate's. A real
     * EditSession writes through to the world, so operations that read back a position
     * they just wrote (gravity, height maps, flood fills) depend on this to preview the
     * same way they commit.
     */
    @Test
    public void readsSeeOwnWrites() throws WorldEditException {
        Vector pos = new Vector(4, 5, 6);
        extent.setBlock(pos, new BaseBlock(BlockID.STONE));

        assertEquals(
            BlockID.STONE,
            extent.getBlock(pos)
                .getType());
        assertEquals(
            BlockID.STONE,
            extent.getLazyBlock(pos)
                .getType());
    }

    @Test
    public void readsFallThroughToDelegateForUntouchedPositions() {
        assertEquals(
            BlockID.AIR,
            extent.getBlock(new Vector(9, 9, 9))
                .getType());
    }

    /**
     * Patterns such as {@link com.sk89q.worldedit.function.pattern.BlockPattern} return the
     * same mutable BaseBlock instance on every call. The normal pipeline consumes it
     * immediately, but a buffer retains it, so without a defensive copy every captured
     * entry would alias a single object and report whatever was written last.
     */
    @Test
    public void capturedBlocksDoNotAliasAReusedInstance() throws WorldEditException {
        BaseBlock reused = new BaseBlock(BlockID.STONE);

        extent.setBlock(new Vector(0, 0, 0), reused);
        reused.setIdAndData(BlockID.DIRT, 0);
        extent.setBlock(new Vector(0, 0, 1), reused);

        assertEquals(
            BlockID.STONE,
            extent.getBlock(new Vector(0, 0, 0))
                .getType());
        assertEquals(
            BlockID.DIRT,
            extent.getBlock(new Vector(0, 0, 1))
                .getType());
    }

    @Test
    public void mutatingAReadResultDoesNotCorruptTheBuffer() throws WorldEditException {
        Vector pos = new Vector(2, 2, 2);
        extent.setBlock(pos, new BaseBlock(BlockID.STONE));

        BaseBlock read = extent.getBlock(pos);
        read.setIdAndData(BlockID.LAVA, 0);

        assertNotSame(read, extent.getBlock(pos));
        assertEquals(
            BlockID.STONE,
            extent.getBlock(pos)
                .getType());
    }

    @Test
    public void tracksBoundsOfChangedArea() throws WorldEditException {
        assertNull(extent.getMinimumChangedPoint());
        assertNull(extent.getMaximumChangedPoint());

        extent.setBlock(new Vector(5, 1, -3), new BaseBlock(BlockID.STONE));
        extent.setBlock(new Vector(-2, 7, 4), new BaseBlock(BlockID.STONE));

        assertEquals(new Vector(-2, 1, -3), extent.getMinimumChangedPoint());
        assertEquals(new Vector(5, 7, 4), extent.getMaximumChangedPoint());
    }

    @Test
    public void overwritingAPositionKeepsASingleEntry() throws WorldEditException {
        Vector pos = new Vector(1, 1, 1);
        extent.setBlock(pos, new BaseBlock(BlockID.STONE));
        extent.setBlock(pos, new BaseBlock(BlockID.DIRT));

        assertEquals(1, extent.size());
        assertEquals(
            BlockID.DIRT,
            extent.getBlock(pos)
                .getType());
    }

    @Test
    public void clearDiscardsPendingChangesAndBounds() throws WorldEditException {
        extent.setBlock(new Vector(1, 1, 1), new BaseBlock(BlockID.STONE));
        extent.clear();

        assertEquals(0, extent.size());
        assertNull(extent.getMinimumChangedPoint());
        // reads fall back through to the delegate again
        assertEquals(
            BlockID.AIR,
            extent.getBlock(new Vector(1, 1, 1))
                .getType());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void pendingChangesViewIsUnmodifiable() {
        extent.getPendingChanges()
            .clear();
    }

}
