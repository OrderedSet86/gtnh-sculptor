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

package com.gtnh.sculptor.brush;

import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * Everything a brush needs to know about how it was configured.
 *
 * <p>
 * Passed by value from the client to the server in the commit packet, so preview and commit run
 * the same brush with the same settings by construction rather than by two call sites agreeing.
 * </p>
 */
public final class BrushParams {

    public final int radius;
    public final BaseBlock material;

    /** Brush-specific second parameter: cylinder height, smooth iterations. */
    public final int secondary;

    /** 0..1, how forcefully a terrain brush acts. */
    public final double strength;

    /**
     * 0..1, how much smoothing to apply after a placement brush. Zero disables it.
     */
    public final double autoSmooth;

    /**
     * When set, shape-preserving brushes build with {@link #material} rather than copying
     * whatever block already forms the surface.
     */
    public final boolean useSelectedMaterial;

    public BrushParams(int radius, BaseBlock material, int secondary, double strength, double autoSmooth,
        boolean useSelectedMaterial) {
        this.radius = radius;
        this.material = material;
        this.secondary = secondary;
        this.strength = strength;
        this.autoSmooth = autoSmooth;
        this.useSelectedMaterial = useSelectedMaterial;
    }

}
