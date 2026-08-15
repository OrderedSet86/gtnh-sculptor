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

/**
 * What to do with the part of a placed shape that has nothing underneath it.
 *
 * <p>
 * A solid shape fills upward from the height it was aimed at, across its whole footprint, so on a
 * slope its downhill part hangs in the air. Smoothing cannot resolve that on its own -- it is a
 * height map and never looks at the underside of anything -- so the shape's own underside has to
 * be dealt with, and there is more than one right answer.
 * </p>
 *
 * <p>
 * Both act only when auto-smooth is on. With the slider at zero a shape keeps exactly the geometry
 * it was drawn with, which is what building in the air needs.
 * </p>
 */
public enum OverhangMode {

    /**
     * Extend the shape down until it rests on the terrain, turning the overhang into a solid mass
     * standing on the hillside.
     */
    SETTLE("Overhang: fill to ground"),

    /**
     * Leave the overhang hanging, and round its underside into the ground where it is anchored --
     * a cantilever rather than a plateau. Material is added where the shape meets the slope and
     * taken away toward the free end, so the underside curves instead of ending in a flat slab.
     */
    CANTILEVER("Overhang: cantilever");

    private final String label;

    OverhangMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public OverhangMode next() {
        OverhangMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Resolve from a wire ordinal, defaulting rather than throwing on a bad value. */
    public static OverhangMode fromOrdinal(int ordinal) {
        OverhangMode[] all = values();
        return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : SETTLE;
    }
}
