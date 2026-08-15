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

package com.gtnh.sculptor.preview;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * A read-only view of the world as it <em>would</em> look with a set of pending changes
 * applied.
 *
 * <p>
 * Positions with a pending change report that change; everything else reads through to the
 * real world. That fall-through matters for more than convenience: {@code RenderBlocks} asks
 * this view about neighbouring blocks to decide which faces to emit, so delegating means the
 * ghost is culled against existing terrain exactly as the committed result would be. A sphere
 * carved inside a hill renders only the faces you would actually end up seeing.
 * </p>
 *
 * <p>
 * This is the same trick Schematica uses to render a schematic overlay, narrowed to a sparse
 * change set rather than a dense array.
 * </p>
 */
public class PreviewWorld implements IBlockAccess {

    /**
     * Above this many cells the change set is left in its map rather than unpacked.
     *
     * <p>
     * The grid is sized by the bounding box, so a brush that touches two far-apart columns would
     * otherwise reserve everything between them. Sixteen megabytes of references is far more than
     * any brush the client can ask for -- a maximum-radius sphere needs 69,000 cells.
     * </p>
     */
    private static final int MAX_GRID_CELLS = 2_000_000;

    private final IBlockAccess delegate;
    private final Map<BlockVector, BaseBlock> pending;

    /**
     * The change set unpacked into its bounding box, or null if it was too sparse to be worth it.
     *
     * <p>
     * This exists because of how much {@code RenderBlocks} asks. Baking one block costs thirteen
     * to twenty queries -- seven brightness lookups, six {@code shouldSideBeRendered} calls, the
     * bounds and the colour -- and each one used to allocate a {@code BlockVector} and probe a
     * map of tens of thousands of entries, whose {@code equals} runs three virtual calls and
     * three double-to-int conversions. A maximum-radius sphere therefore spent over half a
     * million allocations and hash probes on a single bake, and re-baked up to twenty times a
     * second while the crosshair moved. Indexing an array costs neither.
     * </p>
     */
    private final BaseBlock[] grid;
    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;

    public PreviewWorld(IBlockAccess delegate, Map<BlockVector, BaseBlock> pending) {
        this.delegate = delegate;
        this.pending = pending;

        int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
        int highX = Integer.MIN_VALUE, highY = Integer.MIN_VALUE, highZ = Integer.MIN_VALUE;
        for (BlockVector position : pending.keySet()) {
            int x = position.getBlockX(), y = position.getBlockY(), z = position.getBlockZ();
            if (x < lowX) lowX = x;
            if (y < lowY) lowY = y;
            if (z < lowZ) lowZ = z;
            if (x > highX) highX = x;
            if (y > highY) highY = y;
            if (z > highZ) highZ = z;
        }

        long cells = pending.isEmpty() ? 0L : (long) (highX - lowX + 1) * (highY - lowY + 1) * (highZ - lowZ + 1);

        if (cells == 0L || cells > MAX_GRID_CELLS) {
            this.grid = null;
            this.minX = this.minY = this.minZ = 0;
            this.sizeX = this.sizeY = this.sizeZ = 0;
            return;
        }

        this.minX = lowX;
        this.minY = lowY;
        this.minZ = lowZ;
        this.sizeX = highX - lowX + 1;
        this.sizeY = highY - lowY + 1;
        this.sizeZ = highZ - lowZ + 1;

        // References into the map's own blocks, not copies: nothing mutates them after a bake.
        this.grid = new BaseBlock[(int) cells];
        for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
            BlockVector position = entry.getKey();
            grid[index(position.getBlockX(), position.getBlockY(), position.getBlockZ())] = entry.getValue();
        }
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    private BaseBlock pendingAt(int x, int y, int z) {
        if (grid == null) {
            return pending.get(new BlockVector(x, y, z));
        }

        int dx = x - minX, dy = y - minY, dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) return null;
        return grid[(dy * sizeZ + dz) * sizeX + dx];
    }

    /**
     * Whether every face of this block is buried under another pending block.
     *
     * <p>
     * Most of a solid brush is interior. Those blocks emit no quads, because
     * {@code shouldSideBeRendered} rejects all six faces, but only after {@code RenderBlocks} has
     * done the whole per-block dance to find that out -- roughly four blocks in five of a sphere,
     * paying full price for nothing. Asking first is six array reads.
     * </p>
     *
     * <p>
     * Only pending neighbours count. A neighbour that is untouched world might still hide a face,
     * but establishing that costs a real chunk lookup, which is the expense this avoids.
     * </p>
     */
    public boolean isHiddenByPendingNeighbours(int x, int y, int z) {
        return opaquePendingAt(x + 1, y, z) && opaquePendingAt(x - 1, y, z)
            && opaquePendingAt(x, y + 1, z)
            && opaquePendingAt(x, y - 1, z)
            && opaquePendingAt(x, y, z + 1)
            && opaquePendingAt(x, y, z - 1);
    }

    private boolean opaquePendingAt(int x, int y, int z) {
        BaseBlock pendingBlock = pendingAt(x, y, z);
        if (pendingBlock == null) return false;

        Block block = Block.getBlockById(pendingBlock.getType());
        return block != null && block.isOpaqueCube();
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        BaseBlock block = pendingAt(x, y, z);
        return (block != null) ? Block.getBlockById(block.getType()) : delegate.getBlock(x, y, z);
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        BaseBlock block = pendingAt(x, y, z);
        return (block != null) ? block.getData() : delegate.getBlockMetadata(x, y, z);
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        BaseBlock block = pendingAt(x, y, z);
        if (block != null) {
            return Block.getBlockById(block.getType())
                .isAir(this, x, y, z);
        }
        return delegate.isAirBlock(x, y, z);
    }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean defaultValue) {
        BaseBlock block = pendingAt(x, y, z);
        if (block != null) {
            return Block.getBlockById(block.getType())
                .isSideSolid(this, x, y, z, side);
        }
        return delegate.isSideSolid(x, y, z, side, defaultValue);
    }

    /**
     * Tile entities are not previewed. A pending change replaces whatever was there, so
     * reporting the old world's tile entity would render a stale chest or machine inside the
     * ghost.
     */
    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return (pendingAt(x, y, z) != null) ? null : delegate.getTileEntity(x, y, z);
    }

    // Lighting, biome and redstone are read from the real world. The ghost is a preview, not a
    // simulation -- relighting a pending edit is a separate problem and not worth solving to
    // draw a translucent overlay.

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minBlockLight) {
        return delegate.getLightBrightnessForSkyBlocks(x, y, z, minBlockLight);
    }

    @Override
    public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
        return delegate.isBlockProvidingPowerTo(x, y, z, side);
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return delegate.getBiomeGenForCoords(x, z);
    }

    @Override
    public int getHeight() {
        return delegate.getHeight();
    }

    @Override
    public boolean extendedLevelsInChunkCache() {
        return delegate.extendedLevelsInChunkCache();
    }

}
