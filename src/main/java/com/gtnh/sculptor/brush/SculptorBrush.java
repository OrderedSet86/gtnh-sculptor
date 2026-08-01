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

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.command.tool.brush.CylinderBrush;
import com.sk89q.worldedit.command.tool.brush.GravityBrush;
import com.sk89q.worldedit.command.tool.brush.HollowCylinderBrush;
import com.sk89q.worldedit.command.tool.brush.HollowSphereBrush;
import com.sk89q.worldedit.command.tool.brush.SmoothBrush;
import com.sk89q.worldedit.command.tool.brush.SphereBrush;
import com.sk89q.worldedit.function.pattern.BlockPattern;

/**
 * The brushes Sculptor offers, and the single place that says what each one does.
 *
 * <p>
 * The shape brushes are WorldEdit's own {@link Brush} implementations, reused unmodified. They
 * need no changes to be previewable: a brush writes to an {@code EditSession} and never learns
 * whether that session is applying changes or capturing them.
 * </p>
 *
 * <p>
 * The terrain brushes in {@link TerrainBrushes} are ours, because WorldEdit has no equivalent --
 * its brushes are binary, and terrain shaping needs an amount that tapers toward the edge of the
 * stroke.
 * </p>
 *
 * <p>
 * Brushes are constructed per use rather than shared, because several take parameters (a
 * cylinder's height, a smooth's iteration count, a terrain brush's strength) that the user can
 * change between strokes.
 * </p>
 *
 * <p>
 * Preview and commit both dispatch through {@link #apply}, so there is no way for the ghost to
 * disagree with the result: they are the same call against different sessions.
 * </p>
 */
public enum SculptorBrush {

    SPHERE("Sphere", null, 0, 0, false),
    HOLLOW_SPHERE("Hollow sphere", null, 0, 0, false),
    CYLINDER("Cylinder", "Height", 1, 32, false),
    HOLLOW_CYLINDER("Hollow cylinder", "Height", 1, 32, false),
    SMOOTH("Smooth", "Iterations", 1, 8, false),
    GRAVITY("Gravity", null, 0, 0, false),
    RAISE("Raise", null, 0, 0, true),
    LOWER("Lower", null, 0, 0, true),
    FLATTEN("Flatten", null, 0, 0, true),
    ERODE("Erode", null, 0, 0, true),
    MELT("Melt", null, 0, 0, true);

    private final String label;
    private final String secondaryLabel;
    private final int secondaryMin;
    private final int secondaryMax;
    private final boolean usesStrength;

    SculptorBrush(String label, String secondaryLabel, int secondaryMin, int secondaryMax, boolean usesStrength) {
        this.label = label;
        this.secondaryLabel = secondaryLabel;
        this.secondaryMin = secondaryMin;
        this.secondaryMax = secondaryMax;
        this.usesStrength = usesStrength;
    }

    public String label() {
        return label;
    }

    /** Whether this brush has a second integer parameter, and what to call it. */
    public boolean hasSecondary() {
        return secondaryLabel != null;
    }

    public String secondaryLabel() {
        return secondaryLabel;
    }

    public int secondaryMin() {
        return secondaryMin;
    }

    public int secondaryMax() {
        return secondaryMax;
    }

    /** Whether a strength control is meaningful for this brush. */
    public boolean usesStrength() {
        return usesStrength;
    }

    private Brush create(int secondary, double strength) {
        switch (this) {
            case HOLLOW_SPHERE:
                return new HollowSphereBrush();
            case CYLINDER:
                return new CylinderBrush(secondary);
            case HOLLOW_CYLINDER:
                return new HollowCylinderBrush(secondary);
            case SMOOTH:
                return new SmoothBrush(secondary);
            case GRAVITY:
                return new GravityBrush(false);
            case RAISE:
                return new TerrainBrushes.RaiseLower(1, strength);
            case LOWER:
                return new TerrainBrushes.RaiseLower(-1, strength);
            case FLATTEN:
                return new TerrainBrushes.Flatten(strength);
            case ERODE:
                return new TerrainBrushes.Erode(false, strength);
            case MELT:
                return new TerrainBrushes.Erode(true, strength);
            case SPHERE:
            default:
                return new SphereBrush();
        }
    }

    /**
     * Run this brush against a session.
     *
     * @param session   the session to write to -- a preview session captures, a normal one applies
     * @param position  the centre of the brush
     * @param radius    the brush radius
     * @param material  the block to build with; shape-only brushes such as {@link #SMOOTH} ignore it
     * @param secondary the brush's second parameter, if it has one
     * @param strength  0..1, how forcefully a terrain brush acts
     */
    public void apply(EditSession session, Vector position, int radius, BaseBlock material, int secondary,
        double strength) throws MaxChangedBlocksException {
        create(secondary, strength).build(session, position, new BlockPattern(new BaseBlock(material)), radius);
    }

    public SculptorBrush next() {
        SculptorBrush[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public SculptorBrush previous() {
        SculptorBrush[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    /** Resolve from a wire ordinal, defaulting rather than throwing on a bad value. */
    public static SculptorBrush fromOrdinal(int ordinal) {
        SculptorBrush[] all = values();
        return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : SPHERE;
    }

}
