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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.world.World;

/**
 * The single surface every Sculptor brush reads and writes through.
 *
 * <p>
 * A brush does not know whether it is previewing or committing. In preview mode the context
 * holds a {@link PreviewBuffer} and nothing reaches the world; in commit mode it wraps a real
 * {@link EditSession}. Same brush code, same call, two backings -- so the ghost and the result
 * cannot disagree, because they are produced by the same execution.
 * </p>
 *
 * <p>
 * This exists as a class we own rather than as a patch to {@code EditSession} because Sculptor
 * is an addon: WorldEdit ships as its own mod and cannot be modified. That turns out to be the
 * better design anyway. {@code EditSession.getBlock}, {@code getBlockType} and
 * {@code getBlockData} all bypass its own extent chain and read the world directly, so a
 * capture inserted into that chain is invisible to them -- which is exactly why height-map
 * brushes previewed incorrectly under the old approach. Owning both sides of the read closes
 * that off by construction.
 * </p>
 */
public class SculptorEditContext {

    private static final BaseBlock AIR = new BaseBlock(BlockID.AIR);

    /** Non-null when committing. */
    private final EditSession session;

    /** The world to read through for positions no brush has touched. */
    private final World world;

    /** Non-null when previewing. */
    private final PreviewBuffer buffer;

    private PlacementMask placementMask = PlacementMask.OVERWRITE;
    private BaseBlock material = AIR;

    /** Positions this stroke has written, or null when nobody asked to keep track. */
    private Set<BlockVector> written;
    private boolean guardRemovals;

    private int changeCount;

    private SculptorEditContext(EditSession session, World world, PreviewBuffer buffer) {
        this.session = session;
        this.world = world;
        this.buffer = buffer;
    }

    /** Capture changes without touching the world. */
    public static SculptorEditContext preview(World world) {
        return new SculptorEditContext(null, world, new PreviewBuffer());
    }

    /** Apply changes for real, through the given session. */
    public static SculptorEditContext commit(EditSession session) {
        return new SculptorEditContext(session, session.getWorld(), null);
    }

    public boolean isPreview() {
        return buffer != null;
    }

    public World getWorld() {
        return world;
    }

    public void setPlacementMask(PlacementMask mask) {
        this.placementMask = (mask != null) ? mask : PlacementMask.OVERWRITE;
    }

    public PlacementMask getPlacementMask() {
        return placementMask;
    }

    /**
     * Start remembering every position written, so removals can later be told apart from
     * removals of untouched terrain.
     *
     * <p>
     * Off by default, and switched on only for a stroke that is going to need it -- the set
     * grows with the volume of the brush, which at the server's maximum radius is millions of
     * positions.
     * </p>
     */
    public void trackWrites() {
        if (written == null) written = new HashSet<BlockVector>();
    }

    /**
     * Hold removals to the same standard as placements.
     *
     * <p>
     * Normally the mask governs only what may be built <em>over</em>: gating removals as well
     * would mean "air only" silently disabled every brush that carves, since none of them can
     * write air over a block that is by definition not air.
     * </p>
     *
     * <p>
     * The auto-smooth pass is the exception, because the player did not ask for it. Cutting into
     * untouched ground is exactly what "air only" says not to do, so during that pass a removal
     * has to satisfy the mask too -- unless it is undoing something this same stroke placed,
     * which was never protected terrain to begin with. Needs {@link #trackWrites()} to have been
     * on from the start of the stroke.
     * </p>
     */
    public void setGuardRemovals(boolean guard) {
        this.guardRemovals = guard;
    }

    /** The brush material, needed by {@link PlacementMask#REPLACE_MATERIAL}. */
    public void setMaterial(BaseBlock material) {
        this.material = (material != null) ? material : AIR;
    }

    /**
     * Read the world as it stands including anything written through this context.
     *
     * <p>
     * Never returns null; unknown positions read as air, matching how the rest of WorldEdit
     * behaves out of bounds.
     * </p>
     */
    public BaseBlock getBlock(Vector position) {
        if (buffer != null) {
            BaseBlock pending = buffer.get(position);
            if (pending != null) return pending;
        }
        BaseBlock block = (session != null) ? session.getBlock(position) : world.getBlock(position);
        return (block != null) ? block : AIR;
    }

    public boolean isAir(Vector position) {
        return getBlock(position).getType() == BlockID.AIR;
    }

    public boolean isAir(int x, int y, int z) {
        return isAir(new Vector(x, y, z));
    }

    /**
     * Write a block, subject to the placement mask.
     *
     * @return true if the block was written, false if the mask rejected it
     */
    public boolean setBlock(Vector position, BaseBlock block) throws MaxChangedBlocksException {
        // The mask governs what may be built *over*, so it only applies to placing something.
        // Gating removals on it too would mean "air only" silently disabled every brush that
        // carves -- erode, lower, foliage removal -- since none of them can write air over a
        // block that is by definition not air.
        boolean placing = block.getType() != BlockID.AIR;
        BlockVector key = position.toBlockVector();

        boolean ours = written != null && written.contains(key);
        if (placing || (guardRemovals && !ours)) {
            if (!placementMask.allows(getBlock(position), material)) return false;
        }

        if (buffer != null) {
            buffer.put(position, block);
        } else {
            session.setBlock(position, block);
        }
        if (written != null) written.add(key);
        changeCount++;
        return true;
    }

    public boolean setBlock(int x, int y, int z, BaseBlock block) throws MaxChangedBlocksException {
        return setBlock(new Vector(x, y, z), block);
    }

    public boolean setAir(int x, int y, int z) throws MaxChangedBlocksException {
        return setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
    }

    /**
     * Find the highest non-air block in a column.
     *
     * <p>
     * Deliberately not {@code EditSession.getHighestTerrainBlock}: that reads through
     * {@code getBlockType}, which bypasses everything and sees only the untouched world. This
     * one reads through the context, so it sees pending changes and previews correctly.
     * </p>
     *
     * @return the surface Y, or {@link Integer#MIN_VALUE} if the column is empty
     */
    public int surfaceY(int x, int z, int topY, int bottomY) {
        int from = Math.min(255, topY);
        int to = Math.max(0, bottomY);
        for (int y = from; y >= to; y--) {
            if (!isAir(x, y, z)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** The changes captured while previewing, in the order they were made. */
    public Map<BlockVector, BaseBlock> getPendingChanges() {
        return (buffer != null) ? buffer.asMap() : Collections.<BlockVector, BaseBlock>emptyMap();
    }

    /** How many writes the mask let through. */
    public int getChangeCount() {
        return changeCount;
    }

}
