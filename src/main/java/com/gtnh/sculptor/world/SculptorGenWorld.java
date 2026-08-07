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

package com.gtnh.sculptor.world;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * A {@link World} whose block reads and writes go to a {@link SculptorEditContext}.
 *
 * <p>
 * This exists for one reason: vanilla tree generators. {@code WorldGenAbstractTree.generate}
 * takes a concrete {@code net.minecraft.world.World}, not an interface, so there is no way to
 * hand it an {@code IBlockAccess} shim. Without this the tree brush could neither preview nor
 * be undone -- WorldEdit's own {@code generateTree} has exactly that problem, since it ignores
 * the {@code EditSession} it is given and writes straight into the live world.
 * </p>
 *
 * <p>
 * Routing generation through the context instead means a tree previews as a ghost and lands in
 * Sculptor's undo history like any other edit.
 * </p>
 *
 * <p>
 * Only the block-level surface is implemented, which is all a tree generator touches. This
 * world is never ticked, never added to the server's world list, holds no chunks, and no entity
 * ever sees it.
 * </p>
 */
public class SculptorGenWorld extends World {

    private final SculptorEditContext ctx;

    /**
     * Constructed with a throwaway provider rather than the real world's.
     *
     * <p>
     * The five-argument {@code World} constructor only assigns fields --
     * {@code provider.registerWorld(this)} and {@code createChunkProvider()} happen in
     * {@code finishSetup}, which is client-only and called separately. Sharing the real
     * provider would still be a trap: whoever calls {@code registerWorld} next would repoint it
     * at this scratch world.
     * </p>
     */
    public SculptorGenWorld(SculptorEditContext ctx) {
        super(
            (ISaveHandler) null,
            "sculptor-scratch",
            new net.minecraft.world.WorldProviderSurface(),
            new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
            null);
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ soil override

    /**
     * A patch of ground reported to the generator as dirt.
     *
     * <p>
     * Every 1.7.10 tree generator asks the block below its base
     * {@code canSustainPlant(world, x, y - 1, z, UP, Blocks.sapling)}, and Forge's default answer
     * is yes for grass, dirt and farmland and no for everything else. A generator refused this way
     * returns {@code false} without throwing, so planting on sculpted stained clay or stone looked
     * like the brush had simply done nothing.
     * </p>
     *
     * <p>
     * Answering that one read with dirt is enough, and it is the only approach that costs nothing:
     * no block is written, so nothing reaches the preview buffer, the {@code EditSession}, its
     * change set or the undo history, and the terrain the player sculpted is still there
     * afterwards. Swapping the block for real and swapping it back would work too, but it would
     * land in history twice, inflate the "applied N blocks" count, and be rejected outright by a
     * placement mask of air-only or replace-selected -- silently reinstating the bug.
     * </p>
     *
     * <p>
     * Two blocks square, because {@code WorldGenHugeTrees} checks all four columns under its base.
     * </p>
     */
    private boolean soilOverride;
    private int soilX, soilY, soilZ;

    public void setSoilOverride(int x, int y, int z) {
        this.soilOverride = true;
        this.soilX = x;
        this.soilY = y;
        this.soilZ = z;
    }

    public void clearSoilOverride() {
        this.soilOverride = false;
    }

    private boolean isOverriddenSoil(int x, int y, int z) {
        return soilOverride && y == soilY && (x == soilX || x == soilX + 1) && (z == soilZ || z == soilZ + 1);
    }

    // ------------------------------------------------------------------ block access

    @Override
    public Block getBlock(int x, int y, int z) {
        if (y < 0 || y > 255) return Blocks_air();

        BaseBlock block = ctx.getBlock(new Vector(x, y, z));

        // Never lie over air: a tree should still refuse to grow on nothing.
        if (isOverriddenSoil(x, y, z) && block.getType() != BlockID.AIR) {
            return Blocks.dirt;
        }

        return Block.getBlockById(block.getType());
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        if (y < 0 || y > 255) return 0;

        BaseBlock block = ctx.getBlock(new Vector(x, y, z));
        if (isOverriddenSoil(x, y, z) && block.getType() != BlockID.AIR) return 0;

        return block.getData();
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return y < 0 || y > 255 || ctx.isAir(x, y, z);
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
        if (y < 0 || y > 255) return false;

        // The soil is being reported as dirt, and some generators write it back as such --
        // WorldGenMegaPineTree stamps podzol over it outright. Dropping those writes is the whole
        // point: the block the player sculpted with must survive having a tree put on it. Reported
        // as succeeded, since from the generator's side it did.
        if (isOverriddenSoil(x, y, z)) return true;

        try {
            ctx.setBlock(x, y, z, new BaseBlock(Block.getIdFromBlock(block), metadata));
            return true;
        } catch (MaxChangedBlocksException e) {
            // The generator has no way to report this, and a partially-grown tree is better than
            // an exception escaping into the render loop mid-preview.
            return false;
        }
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block) {
        return setBlock(x, y, z, block, 0, 3);
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
        return setBlock(x, y, z, getBlock(x, y, z), metadata, flags);
    }

    /** No tile entities in a scratch world; a generator that asks gets nothing rather than a crash. */
    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return null;
    }

    @Override
    public void setTileEntity(int x, int y, int z, TileEntity tileEntity) {}

    @Override
    public void removeTileEntity(int x, int y, int z) {}

    // ------------------------------------------------------------------ world queries

    /**
     * Biome comes from the real world through WorldEdit, so a tree planted in a taiga is a taiga
     * tree even though this scratch world has no biome data of its own.
     */
    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        try {
            int id = ctx.getWorld()
                .getBiome(new Vector2D(x, z))
                .getId();
            BiomeGenBase biome = BiomeGenBase.getBiome(id);
            if (biome != null) return biome;
        } catch (RuntimeException ignored) {
            // Fall through to a sane default rather than failing the whole generation.
        }
        return BiomeGenBase.plains;
    }

    @Override
    public int getHeightValue(int x, int z) {
        for (int y = 255; y >= 0; y--) {
            if (!isAirBlock(x, y, z)) return y + 1;
        }
        return 0;
    }

    /** Generators use this to refuse to grow in a cave; treat anything with clear air above as open. */
    @Override
    public boolean canBlockSeeTheSky(int x, int y, int z) {
        for (int above = y + 1; above <= 255; above++) {
            if (!isAirBlock(x, above, z)) return false;
        }
        return true;
    }

    @Override
    public int getFullBlockLightValue(int x, int y, int z) {
        return canBlockSeeTheSky(x, y, z) ? 15 : 0;
    }

    @Override
    public int getBlockLightValue(int x, int y, int z) {
        return getFullBlockLightValue(x, y, z);
    }

    @Override
    public boolean blockExists(int x, int y, int z) {
        return y >= 0 && y <= 255;
    }

    @Override
    public boolean checkChunksExist(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return true;
    }

    // ------------------------------------------------------------------ inert plumbing

    @Override
    protected IChunkProvider createChunkProvider() {
        // Never called: only finishSetup() invokes this, and that is client-only and not used.
        return null;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    /** Render distance in chunks. Nothing renders this world, so the value is arbitrary. */
    @Override
    public int func_152379_p() {
        return 0;
    }

    private static Block Blocks_air() {
        return Blocks.air;
    }

}
