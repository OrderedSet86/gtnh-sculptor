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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

public class PreviewBufferTest {

    private PreviewBuffer buffer;

    @Before
    public void setUp() {
        buffer = new PreviewBuffer();
    }

    @Test
    public void recordsAndReadsBack() {
        buffer.put(new Vector(1, 2, 3), new BaseBlock(BlockID.STONE));

        assertEquals(1, buffer.size());
        assertEquals(
            BlockID.STONE,
            buffer.get(new Vector(1, 2, 3))
                .getType());
    }

    @Test
    public void untouchedPositionsReadNull() {
        assertNull(buffer.get(new Vector(9, 9, 9)));
    }

    /**
     * Patterns hand back the same mutable BaseBlock on every call. A buffer retains what it is
     * given, so without a defensive copy every entry would alias one object and report whatever
     * was written last.
     */
    @Test
    public void doesNotAliasAReusedBlockInstance() {
        BaseBlock reused = new BaseBlock(BlockID.STONE);

        buffer.put(new Vector(0, 0, 0), reused);
        reused.setIdAndData(BlockID.DIRT, 0);
        buffer.put(new Vector(0, 0, 1), reused);

        assertEquals(
            BlockID.STONE,
            buffer.get(new Vector(0, 0, 0))
                .getType());
        assertEquals(
            BlockID.DIRT,
            buffer.get(new Vector(0, 0, 1))
                .getType());
    }

    @Test
    public void overwritingAPositionKeepsOneEntry() {
        Vector pos = new Vector(1, 1, 1);
        buffer.put(pos, new BaseBlock(BlockID.STONE));
        buffer.put(pos, new BaseBlock(BlockID.DIRT));

        assertEquals(1, buffer.size());
        assertEquals(
            BlockID.DIRT,
            buffer.get(pos)
                .getType());
    }

    @Test
    public void tracksBounds() {
        assertNull(buffer.getMinimumPoint());

        buffer.put(new Vector(5, 1, -3), new BaseBlock(BlockID.STONE));
        buffer.put(new Vector(-2, 7, 4), new BaseBlock(BlockID.STONE));

        assertEquals(new Vector(-2, 1, -3), buffer.getMinimumPoint());
        assertEquals(new Vector(5, 7, 4), buffer.getMaximumPoint());
    }

    @Test
    public void clearDiscardsEverything() {
        buffer.put(new Vector(1, 1, 1), new BaseBlock(BlockID.STONE));
        buffer.clear();

        assertTrue(buffer.isEmpty());
        assertNull(buffer.getMinimumPoint());
        assertNull(buffer.get(new Vector(1, 1, 1)));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void exposedMapIsUnmodifiable() {
        buffer.asMap()
            .clear();
    }

}
