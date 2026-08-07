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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * Block changes a brush would make, held rather than applied.
 *
 * <p>
 * This is <strong>read-your-writes</strong>, which is the whole point of it. Brushes such as
 * smooth, gravity and erode read back positions they have just written -- smoothing samples a
 * height map, gravity walks a column. A buffer that answered reads from the untouched world
 * would make those brushes preview differently from how they commit, which is precisely the
 * lie a preview must not tell.
 * </p>
 */
public class PreviewBuffer {

    private final Map<BlockVector, BaseBlock> changes = new LinkedHashMap<BlockVector, BaseBlock>();

    private Vector min;
    private Vector max;

    /**
     * Record a change.
     *
     * <p>
     * The block is copied on the way in. Patterns such as {@code BlockPattern} return the same
     * mutable {@link BaseBlock} on every call; the normal pipeline consumes it immediately, but
     * a buffer retains it, so storing the reference would alias every entry to one object.
     * </p>
     */
    public void put(Vector position, BaseBlock block) {
        min = (min == null) ? position : Vector.getMinimum(min, position);
        max = (max == null) ? position : Vector.getMaximum(max, position);
        changes.put(position.toBlockVector(), new BaseBlock(block));
    }

    /**
     * @return the pending block at this position, or null if untouched
     */
    public BaseBlock get(Vector position) {
        return changes.get(position.toBlockVector());
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public int size() {
        return changes.size();
    }

    public Vector getMinimumPoint() {
        return min;
    }

    public Vector getMaximumPoint() {
        return max;
    }

    /**
     * @return an unmodifiable view of the changes, in the order they were made
     */
    public Map<BlockVector, BaseBlock> asMap() {
        return Collections.unmodifiableMap(changes);
    }

    public void clear() {
        changes.clear();
        min = null;
        max = null;
    }

}
