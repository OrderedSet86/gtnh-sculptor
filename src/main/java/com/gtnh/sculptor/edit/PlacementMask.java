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

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * What a brush is allowed to write over.
 *
 * <p>
 * Applied inside {@link SculptorEditContext#setBlock}, so it constrains preview and commit
 * identically and by the same code -- there is no way for the ghost to show a block the commit
 * would then refuse to place.
 * </p>
 *
 * <p>
 * Note this is not WorldEdit's {@code -a} switch, despite sounding like it. That one is a
 * <em>source</em> mask meaning "skip air in the clipboard"; this constrains the destination.
 * </p>
 */
public enum PlacementMask {

    /** Replace whatever is there. */
    OVERWRITE("Overwrite anything") {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            return true;
        }

        @Override
        public boolean inspectsExisting() {
            return false;
        }
    },

    /** Build into empty space only, leaving existing terrain and structures untouched. */
    AIR_ONLY("Air only") {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            return existing == null || existing.getType() == BlockID.AIR;
        }
    },

    /**
     * Only overwrite blocks that already match the brush material.
     *
     * <p>
     * Useful for reshaping something you previously placed without disturbing anything it was
     * built against. Data value is compared too, so coloured or rotated variants stay distinct.
     * </p>
     */
    REPLACE_MATERIAL("Replace selected only") {

        @Override
        public boolean allows(BaseBlock existing, BaseBlock material) {
            if (existing == null || material == null) return false;
            return existing.getType() == material.getType() && existing.getData() == material.getData();
        }
    };

    private final String label;

    PlacementMask(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param existing the block currently at the target position, or null if unknown
     * @param material the brush's chosen material
     */
    public abstract boolean allows(BaseBlock existing, BaseBlock material);

    /**
     * Whether {@link #allows} actually looks at what is in the way.
     *
     * <p>
     * Reading the existing block costs three chunk lookups, a registry probe, a tile-entity
     * lookup and an allocation, and Java evaluates the argument whether or not the mask wants it.
     * The default mask discards it, so a brush placing tens of thousands of blocks was paying all
     * of that for every one of them and throwing the answer away.
     * </p>
     */
    public boolean inspectsExisting() {
        return true;
    }

    public PlacementMask next() {
        PlacementMask[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

}
