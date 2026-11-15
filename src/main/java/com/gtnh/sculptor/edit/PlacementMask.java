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
     * Write over the replace block only. With dirt as the replace block, a stone brush turns dirt into stone, and the
     * other blocks stay unchanged. The data value must match too, so a colour or a rotation of the block is a different
     * block. A removal is checked too, so an air brush removes the replace block only.
     */
    REPLACE_BLOCK {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock replaceBlock) {
            if (existing == null || replaceBlock == null) return false;
            return existing.getType() == replaceBlock.getType() && existing.getData() == replaceBlock.getData();
        }

        @Override
        public boolean checksRemovals() {
            return true;
        }
    };

    /**
     * Report whether the mask allows a write over {@code existing}. {@code existing} is {@code null} when unknown.
     *
     * @param replaceBlock the block that a brush may write over under {@link #REPLACE_BLOCK}
     */
    public abstract boolean allows(BaseBlock existing, BaseBlock replaceBlock);

    /**
     * Report whether {@link #allows} reads {@code existing}. A read of the existing block costs three chunk lookups, a
     * registry lookup, a tile entity lookup and an allocation, and a stroke writes tens of thousands of blocks.
     */
    public boolean inspectsExisting() {
        return true;
    }

    /**
     * Report whether the mask applies to every removal. The other masks apply to a removal only during the auto-smooth
     * pass, under {@link SculptorEditContext#setGuardRemovals}: under {@link #AIR_ONLY}, a carving brush writes air
     * over blocks that are not air.
     */
    public boolean checksRemovals() {
        return false;
    }

    public String getLangKey() {
        return "sculptor.mask." + name().toLowerCase(Locale.ROOT);
    }

    public PlacementMask next() {
        final PlacementMask[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

}
