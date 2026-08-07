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

import com.gtnh.sculptor.edit.SculptorEditContext;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;

/**
 * The brushes Sculptor offers, and the single place that says what each one does.
 *
 * <p>
 * Preview and commit both dispatch through {@link #apply}, so there is no way for the ghost to
 * disagree with the result: they are the same call against a context backed differently.
 * </p>
 */
public enum SculptorBrush {

    SPHERE("Sphere", null, 0, 0, false, true),
    HOLLOW_SPHERE("Hollow sphere", null, 0, 0, false, true),
    CYLINDER("Cylinder", "Height", 1, 32, false, true),
    HOLLOW_CYLINDER("Hollow cylinder", "Height", 1, 32, false, true),
    SMOOTH("Smooth", "Iterations", 1, 8, true, false),
    GRAVITY("Gravity", null, 0, 0, false, false),
    RAISE("Raise", null, 0, 0, true, false),
    LOWER("Lower", null, 0, 0, true, false),
    FLATTEN("Flatten", null, 0, 0, true, false),
    ERODE("Erode", null, 0, 0, true, false),
    MELT("Melt", null, 0, 0, true, false),
    REMOVE_FOLIAGE("Remove foliage", null, 0, 0, false, false);

    private final String label;
    private final String secondaryLabel;
    private final int secondaryMin;
    private final int secondaryMax;
    private final boolean usesStrength;

    /**
     * Whether this brush places a shape rather than reworking existing terrain. Only these get
     * the optional smoothing pass afterwards -- smoothing the output of a smooth brush is not a
     * meaningful thing to offer.
     */
    private final boolean placement;

    SculptorBrush(String label, String secondaryLabel, int secondaryMin, int secondaryMax, boolean usesStrength,
        boolean placement) {
        this.label = label;
        this.secondaryLabel = secondaryLabel;
        this.secondaryMin = secondaryMin;
        this.secondaryMax = secondaryMax;
        this.usesStrength = usesStrength;
        this.placement = placement;
    }

    public String label() {
        return label;
    }

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

    public boolean usesStrength() {
        return usesStrength;
    }

    public boolean isPlacement() {
        return placement;
    }

    /**
     * Run this brush.
     *
     * @param ctx    preview or commit backing -- the brush does not know which
     * @param centre where the crosshair is pointing
     */
    public void apply(SculptorEditContext ctx, Vector centre, BrushParams params) throws MaxChangedBlocksException {
        ctx.setMaterial(params.material);

        switch (this) {
            case SPHERE:
                Brushes.sphere(ctx, centre, params, false);
                break;
            case HOLLOW_SPHERE:
                Brushes.sphere(ctx, centre, params, true);
                break;
            case CYLINDER:
                Brushes.cylinder(ctx, centre, params, false);
                break;
            case HOLLOW_CYLINDER:
                Brushes.cylinder(ctx, centre, params, true);
                break;
            case SMOOTH:
                Brushes.smooth(ctx, centre, params, params.secondary);
                break;
            case GRAVITY:
                Brushes.gravity(ctx, centre, params);
                break;
            case RAISE:
                Brushes.raiseLower(ctx, centre, params, 1);
                break;
            case LOWER:
                Brushes.raiseLower(ctx, centre, params, -1);
                break;
            case FLATTEN:
                Brushes.flatten(ctx, centre, params);
                break;
            case ERODE:
                Brushes.erode(ctx, centre, params, false);
                break;
            case MELT:
                Brushes.erode(ctx, centre, params, true);
                break;
            case REMOVE_FOLIAGE:
                Brushes.removeFoliage(ctx, centre, params);
                break;
            default:
                break;
        }

        if (placement && params.autoSmooth > 0.0D) {
            // Runs through the same context, so in commit mode it lands in the same undo entry
            // and in preview mode the ghost already shows the smoothed result.
            //
            // A wider radius than the placement itself, so the new shape is blended into the
            // ground around it rather than having its own edge smoothed in isolation.
            BrushParams smoothing = new BrushParams(
                params.radius + 2,
                params.material,
                params.secondary,
                params.autoSmooth,
                0.0D,
                params.useSelectedMaterial);
            Brushes.smooth(ctx, centre, smoothing, 2);
        }
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
