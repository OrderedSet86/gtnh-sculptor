package com.gtnh.sculptor.world;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * A {@link World} that reads and writes blocks through a {@link SculptorEditContext}, for the vanilla tree generators.
 *
 * <p>
 * {@code WorldGenAbstractTree.generate()} takes a {@code World}, not an {@code IBlockAccess}. The
 * {@code generateTree()} of WorldEdit writes to the world and not to the {@code EditSession} argument, so for a tree
 * from WorldEdit there is no preview and no undo. Through this world, a tree is a write to the context like any other.
 * </p>
 *
 * <p>
 * The class implements the block methods that a tree generator calls. The world is never ticked or registered, and has
 * no chunks and no entities.
 * </p>
 */
public class SculptorGenWorld extends World {

    private final SculptorEditContext ctx;

    /**
     * Create the world with a new {@link WorldProviderSurface}, not the provider of the real world. The next call to
     * {@code registerWorld()} on a shared provider would point the provider at this world. The 5-argument {@code World}
     * constructor has only field assignments.
     */
    public SculptorGenWorld(SculptorEditContext ctx) {
        super(
            (ISaveHandler) null,
            "sculptor-scratch",
            new WorldProviderSurface(),
            new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
            null);
        this.ctx = ctx;
    }

    private boolean soilOverride;
    private int soilX, soilY, soilZ;

    /**
     * Report the 2 by 2 blocks from ({@code x}, {@code y}, {@code z}) to +X and +Z as dirt to the generator, with no
     * write.
     *
     * <p>
     * A 1.7.10 tree generator calls {@code canSustainPlant(..., UP, Blocks.sapling)} on the block under the base of the
     * tree, and the default answer of Forge is yes for grass, dirt and farmland only. A refused generator returns
     * {@code false} with no error. {@code WorldGenHugeTrees} checks the four columns under the base of the tree, so the
     * patch is 2 by 2.
     * </p>
     *
     * <p>
     * A real write of dirt and a write back would go into the history twice, add to the count of changed blocks, and
     * fail under the masks {@code AIR_ONLY} and {@code REPLACE_MATERIAL}.
     * </p>
     */
    public void setSoilOverride(int x, int y, int z) {
        this.soilOverride = true;
        this.soilX = x;
        this.soilY = y;
        this.soilZ = z;
    }

    public void clearSoilOverride() {
        this.soilOverride = false;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        if (y < 0 || y > 255) return Blocks.air;

        BaseBlock block = ctx.getBlock(new Vector(x, y, z));

        // air stays air, so a tree does not grow on nothing
        if (isOverriddenSoil(x, y, z) && block.getType() != BlockID.AIR) {
            return Blocks.dirt;
        }

        return Block.getBlockById(block.getType());
    }

    private boolean isOverriddenSoil(int x, int y, int z) {
        return soilOverride && y == soilY && (x == soilX || x == soilX + 1) && (z == soilZ || z == soilZ + 1);
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

    /** Write the block through the context. A write over the soil patch of {@link #setSoilOverride} is skipped. */
    @Override
    public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
        if (y < 0 || y > 255) return false;

        // some generators write over the soil, such as the podzol of WorldGenMegaPineTree. Skip the write, so the block
        // the player placed stays, and report success to the generator
        if (isOverriddenSoil(x, y, z)) return true;

        try {
            ctx.setBlock(new Vector(x, y, z), new BaseBlock(Block.getIdFromBlock(block), metadata));
            return true;
        } catch (MaxChangedBlocksException exception) {
            // for a generator, a false return is a failed write. The tree stays partly grown
            return false;
        }
    }

    /** Write the block with metadata 0, through the 6-argument {@code setBlock()}. */
    @Override
    public boolean setBlock(int x, int y, int z, Block block) {
        return setBlock(x, y, z, block, 0, 3);
    }

    /** Write the metadata with the block at the position, through the 6-argument {@code setBlock()}. */
    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
        return setBlock(x, y, z, getBlock(x, y, z), metadata, flags);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return null;
    }

    /** A scratch world has no tile entities, so the call does nothing. */
    @Override
    public void setTileEntity(int x, int y, int z, TileEntity tileEntity) {}

    @Override
    public void removeTileEntity(int x, int y, int z) {}

    /** The biome of the real world, through WorldEdit, so a tree planted in a taiga is a taiga tree. */
    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        try {
            final int id = ctx.getWorld()
                .getBiome(new Vector2D(x, z))
                .getId();
            final BiomeGenBase biome = BiomeGenBase.getBiome(id);
            if (biome != null) return biome;
        } catch (RuntimeException exception) { // noqa: NAR142, the biome lookup of WorldEdit runs world code of
            // other mods. After a failed lookup, the tree is a plains tree, and the rest of the stroke runs
            SculptorMod.LOG.debug("trees.biome_failed x={} z={}", x, z, exception);
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

    // a generator grows no tree in a cave. A block with air up to the top of the world is open to the sky
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

    /** Return {@code true}, so a generator skips no position for a missing chunk. */
    @Override
    public boolean checkChunksExist(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return true;
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        // only finishSetup() calls this, and this world never calls finishSetup()
        return null;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    // the render distance in chunks. Nothing renders this world
    @Override
    public int func_152379_p() {
        return 0;
    }

}
