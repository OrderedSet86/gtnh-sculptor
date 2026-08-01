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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;

/**
 * Captures block changes instead of applying them, so that a pending edit can be
 * rendered as a preview before the user commits to it.
 *
 * <p>
 * This differs from {@link ForgetfulExtentBuffer} in one essential way: it is
 * <strong>read-your-writes</strong>. {@code ForgetfulExtentBuffer} deliberately
 * does not consult its own buffer from {@link #getBlock(Vector)}, so reads fall
 * through to the unmodified delegate. That is fine for its original purpose
 * (buffering a write-only stream) but wrong for previewing: a real
 * {@code EditSession} writes straight to the world, so an operation that reads
 * back a position it just wrote sees the new block. Operations that do exactly
 * that -- gravity, smoothing/height maps, and flood fills -- would otherwise
 * preview differently from how they commit.
 * </p>
 *
 * <p>
 * Blocks are defensively copied on the way in. Patterns such as
 * {@link com.sk89q.worldedit.function.pattern.BlockPattern} return the same
 * mutable {@link BaseBlock} instance on every call; the normal pipeline consumes
 * it immediately, but a buffer retains it, so storing the reference would alias
 * every entry to a single object.
 * </p>
 */
public class PreviewExtent extends AbstractDelegateExtent {

    private final Map<BlockVector, BaseBlock> buffer = new LinkedHashMap<BlockVector, BaseBlock>();
    private Vector min = null;
    private Vector max = null;

    /**
     * Create a new preview extent.
     *
     * @param delegate the extent to read through to for unmodified positions
     */
    public PreviewExtent(Extent delegate) {
        super(delegate);
        checkNotNull(delegate);
    }

    @Override
    public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
        checkNotNull(location);
        checkNotNull(block);

        min = (min == null) ? location : Vector.getMinimum(min, location);
        max = (max == null) ? location : Vector.getMaximum(max, location);

        buffer.put(location.toBlockVector(), new BaseBlock(block));
        return true;
    }

    @Override
    public BaseBlock getBlock(Vector position) {
        BaseBlock block = buffer.get(position.toBlockVector());
        // getBlock() contractually returns a mutable snapshot, so hand back a copy
        // rather than let a caller mutate the pending change in place.
        return (block != null) ? new BaseBlock(block) : super.getBlock(position);
    }

    @Override
    public BaseBlock getLazyBlock(Vector position) {
        BaseBlock block = buffer.get(position.toBlockVector());
        return (block != null) ? block : super.getLazyBlock(position);
    }

    /**
     * Get the pending changes captured so far.
     *
     * @return an unmodifiable view of the buffered changes, in insertion order
     */
    public Map<BlockVector, BaseBlock> getPendingChanges() {
        return Collections.unmodifiableMap(buffer);
    }

    /**
     * Get the number of positions with a pending change.
     *
     * @return the change count
     */
    public int size() {
        return buffer.size();
    }

    /**
     * Get the minimum corner of the affected area.
     *
     * @return the minimum point, or null if nothing has been changed
     */
    public Vector getMinimumChangedPoint() {
        return min;
    }

    /**
     * Get the maximum corner of the affected area.
     *
     * @return the maximum point, or null if nothing has been changed
     */
    public Vector getMaximumChangedPoint() {
        return max;
    }

    /**
     * Discard all pending changes.
     */
    public void clear() {
        buffer.clear();
        min = null;
        max = null;
    }

}
