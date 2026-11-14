package com.gtnh.sculptor.brush;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * Classify a block as foliage or as a tree trunk, for vanilla and modded blocks.
 *
 * <p>
 * Foliage is not ground: a height-map brush skips foliage in the search for the surface. A trunk is not foliage, so to
 * a height-map brush a trunk is ground. The foliage removal brush removes foliage and trunks.
 * </p>
 */
public final class Vegetation {

    private static final int BLOCK_ID_COUNT = 4096;
    private static final byte UNKNOWN = 0;
    private static final byte IS_LOG = 1;
    private static final byte NOT_LOG = 2;

    // one entry per block id, filled on the first query. The result for an id never changes while the game runs
    private static final byte[] LOG_CACHE = new byte[BLOCK_ID_COUNT];

    private Vegetation() {}

    /** Report whether the foliage removal brush removes the block: foliage, or a tree trunk. */
    public static boolean isVegetation(BaseBlock block) {
        return isFoliage(block) || isLog(block);
    }

    /**
     * Report whether the block is foliage: leaves, grass, flowers, saplings, vines or mushrooms.
     *
     * <p>
     * The block class is checked before the material. Mods define new {@code Material} objects for leaves and plants,
     * and materials compare by identity. A check of the material alone returns false for most of the flora of GTNH.
     * </p>
     */
    public static boolean isFoliage(BaseBlock block) {
        final int id = block.getType();
        if (id == BlockID.AIR) return false;
        // these blocks have no foliage material
        if (id == BlockID.BROWN_MUSHROOM_CAP || id == BlockID.RED_MUSHROOM_CAP || id == BlockID.DOUBLE_PLANT) {
            return true;
        }

        final Block mcBlock = Block.getBlockById(id);
        if (mcBlock == null) return false;

        if (mcBlock instanceof BlockLeavesBase || mcBlock instanceof BlockBush || mcBlock instanceof BlockVine) {
            return true;
        }

        final Material material = mcBlock.getMaterial();
        return material == Material.leaves || material == Material.plants || material == Material.vine;
    }

    /**
     * Report whether the block is a tree trunk, modded trunks included.
     *
     * <p>
     * {@code BlockBOPLog} of Biomes O' Plenty extends {@code Block}, not {@code BlockLog}. The wood {@link Material}
     * also matches planks. So the checks are, cheapest first: the class {@code BlockLog}, the ore dictionary name
     * {@code logWood}, which Forge registers for vanilla logs and Biomes O' Plenty registers for the logs of Biomes O'
     * Plenty, and then {@code Block.isWood()} of Forge.
     * </p>
     */
    public static boolean isLog(BaseBlock block) {
        final int id = block.getType();
        if (id <= 0 || id >= LOG_CACHE.length) return false;

        // the ore dictionary check makes an ItemStack, and the removal brush calls isLog() for every block in a sphere
        final byte cached = LOG_CACHE[id];
        if (cached != UNKNOWN) return cached == IS_LOG;

        final boolean log = computeIsLog(id);
        LOG_CACHE[id] = log ? IS_LOG : NOT_LOG;
        return log;
    }

    private static boolean computeIsLog(int id) {
        final Block mcBlock = Block.getBlockById(id);
        if (mcBlock == null) return false;
        if (mcBlock instanceof BlockLog) return true;

        final Item item = Item.getItemFromBlock(mcBlock);
        if (item != null) {
            final int logWood = OreDictionary.getOreID("logWood");
            for (int ore : OreDictionary.getOreIDs(new ItemStack(item, 1, 0))) {
                if (ore == logWood) return true;
            }
        }

        // there is no world to pass, so the call gets null. Vanilla and Biomes O' Plenty ignore the world
        try {
            return mcBlock.isWood(null, 0, 0, 0);
        } catch (RuntimeException exception) { // noqa: NAR142, isWood() of a mod can throw on the null world
            return false;
        }
    }
}
