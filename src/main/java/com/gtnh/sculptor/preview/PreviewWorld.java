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
 * A read-only view of the world with the changes of a preview applied, for {@code RenderBlocks}.
 *
 * <p>
 * A position with a change reads the change, and any other position reads the world. {@code RenderBlocks} reads the
 * neighbours of a block to cull faces, so the ghost is culled against the terrain as the committed result is.
 * </p>
 */
public class PreviewWorld implements IBlockAccess {

    // the grid spans the bounding box of the changes, so two distant columns would reserve the space between them. A
    // sphere of the maximum client radius of 20 needs about 69,000 cells
    private static final int MAX_GRID_CELLS = 2_000_000;

    private final IBlockAccess delegate;
    private final Map<BlockVector, BaseBlock> pending;

    // the changes unpacked into their bounding box, or null above MAX_GRID_CELLS. RenderBlocks makes 13 to 20 queries
    // per block. A map lookup allocates a BlockVector, and BlockVector.equals() makes three virtual calls, so a bake of
    // a large sphere through the map would make over half a million allocations. An array index allocates nothing
    private final BaseBlock[] grid;
    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;

    /**
     * Build the view, and unpack the changes into an array over their bounding box when the box has at most
     * {@link #MAX_GRID_CELLS} cells.
     */
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

        // the grid refers to the blocks of the map, not to copies. Nothing changes a block of a preview after the bake
        this.grid = new BaseBlock[(int) cells];
        for (Map.Entry<BlockVector, BaseBlock> entry : pending.entrySet()) {
            BlockVector position = entry.getKey();
            grid[index(position.getBlockX(), position.getBlockY(), position.getBlockZ())] = entry.getValue();
        }
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    /**
     * Report whether a changed block that is opaque is on each of the six sides. Most of a solid brush is interior, and
     * {@code RenderBlocks} draws no face of an interior block, but only after the full work for the block. This check
     * is six array reads. An unchanged neighbour is not checked, because that check costs a chunk lookup.
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

    private BaseBlock pendingAt(int x, int y, int z) {
        if (grid == null) {
            return pending.get(new BlockVector(x, y, z));
        }

        int dx = x - minX, dy = y - minY, dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) return null;
        return grid[(dy * sizeZ + dz) * sizeX + dx];
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

    /** The side solidity of the changed block, or of the world block at a position with no change. */
    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean defaultValue) {
        BaseBlock block = pendingAt(x, y, z);
        if (block != null) {
            return Block.getBlockById(block.getType())
                .isSideSolid(this, x, y, z, side);
        }
        return delegate.isSideSolid(x, y, z, side, defaultValue);
    }

    // a changed position has no tile entity. With the tile entity of the world, the ghost would include a chest or
    // machine from the world
    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return (pendingAt(x, y, z) != null) ? null : delegate.getTileEntity(x, y, z);
    }

    // light, biome and redstone power come from the world, with no relight of the changes

    /** The light of the world, with no relight of the changes. */
    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minBlockLight) {
        return delegate.getLightBrightnessForSkyBlocks(x, y, z, minBlockLight);
    }

    /** The redstone power of the world, with no change applied. */
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
