package com.gtnh.sculptor.edit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * The block writes of a preview, kept in memory and never applied.
 *
 * <p>
 * The buffer is read-your-writes: {@link #get} returns the last write to a position. Smooth, gravity and erode read
 * positions that the same stroke wrote. A preview of those brushes matches the commit only when the read returns the
 * write.
 * </p>
 */
public class PreviewBuffer {

    private final Map<BlockVector, BaseBlock> changes = new LinkedHashMap<>();

    private Vector min;
    private Vector max;

    /**
     * Record a write. A pattern such as {@code BlockPattern} returns the same mutable {@link BaseBlock} on each call,
     * so {@link #put} records a copy of the block.
     */
    public void put(Vector position, BaseBlock block) {
        min = (min == null) ? position : Vector.getMinimum(min, position);
        max = (max == null) ? position : Vector.getMaximum(max, position);
        changes.put(position.toBlockVector(), new BaseBlock(block));
    }

    /** The last block written at the position, or {@code null} for a position with no write. */
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

    /** An unmodifiable view of the writes, in the order of the first write to each position. */
    public Map<BlockVector, BaseBlock> asMap() {
        return Collections.unmodifiableMap(changes);
    }

    public void clear() {
        changes.clear();
        min = null;
        max = null;
    }

}
