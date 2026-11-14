package com.gtnh.sculptor.edit;

import java.util.Locale;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The blocks that a brush may write over. {@link SculptorEditContext#setBlock} applies the mask, so the preview and the
 * commit apply the mask with the same code.
 *
 * <p>
 * The {@code -a} flag of WorldEdit is a different thing: the flag skips the air of the source clipboard. This mask
 * applies to the destination.
 * </p>
 */
public enum PlacementMask {

    /** Write over any block. */
    OVERWRITE {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            return true;
        }

        @Override
        public boolean inspectsExisting() {
            return false;
        }
    },

    /** Write over air only, so the terrain and builds stay as they are. */
    AIR_ONLY {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            return existing == null || existing.getType() == BlockID.AIR;
        }
    },

    /**
     * Write over a block of the brush material only, to reshape a placed build without a change to the blocks around
     * the build. The data value must match too, so a colour or a rotation of the block is a different block.
     */
    REPLACE_MATERIAL {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            if (existing == null || material == null) return false;
            return existing.getType() == material.getType() && existing.getData() == material.getData();
        }
    };

    /**
     * Report whether the mask allows a write over {@code existing}, with the brush material {@code material}.
     * {@code existing} is {@code null} when unknown.
     */
    public abstract boolean allows(BaseBlock existing, BaseBlock material);

    /**
     * Report whether {@link #allows} reads {@code existing}. A read of the existing block costs three chunk lookups, a
     * registry lookup, a tile entity lookup and an allocation, and a stroke writes tens of thousands of blocks.
     */
    public boolean inspectsExisting() {
        return true;
    }

    public String getLangKey() {
        return "sculptor.mask." + name().toLowerCase(Locale.ROOT);
    }

    public PlacementMask next() {
        final PlacementMask[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

}
