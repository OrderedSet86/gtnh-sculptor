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

    private final IBlockAccess delegate;
    private final Map<BlockVector, BaseBlock> pending;

    public PreviewWorld(IBlockAccess delegate, Map<BlockVector, BaseBlock> pending) {
        this.delegate = delegate;
        this.pending = pending;
    }

    private BaseBlock pendingAt(int x, int y, int z) {
        // WorldEdit's Vector is immutable, so the key has to be allocated. Bakes are
        // infrequent (once per brush move, not per frame), so this is not worth optimising
        // into a packed-long map until profiling says otherwise.
        return pending.get(new BlockVector(x, y, z));
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
