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
 * These are WorldEdit's own {@link Brush} implementations. They need no modification to be
 * previewable: a brush writes to an {@code EditSession} and never learns whether that session
 * is applying changes or capturing them.
 * </p>
 *
 * <p>
 * {@link #SMOOTH} and {@link #GRAVITY} are worth calling out. Both read back positions they
 * have just written -- smoothing reads a height map, gravity walks a column -- which is
 * exactly the case that would preview differently from how it commits if the capture buffer
 * were not read-your-writes.
 * </p>
 *
 * <p>
 * Preview and commit both dispatch through {@link #apply}, so there is no way for the ghost to
 * disagree with the result: they are the same call against different sessions.
 * </p>
 */
public enum SculptorBrush {

    SPHERE("Sphere", new SphereBrush()),
    HOLLOW_SPHERE("Hollow sphere", new HollowSphereBrush()),
    CYLINDER("Cylinder", new CylinderBrush(1)),
    HOLLOW_CYLINDER("Hollow cylinder", new HollowCylinderBrush(1)),
    SMOOTH("Smooth", new SmoothBrush(4)),
    GRAVITY("Gravity", new GravityBrush(false));

    private final String label;
    private final Brush brush;

    SculptorBrush(String label, Brush brush) {
        this.label = label;
        this.brush = brush;
    }

    public String label() {
        return label;
    }

    /**
     * Run this brush against a session.
     *
     * @param session  the session to write to -- a preview session captures, a normal one applies
     * @param position the centre of the brush
     * @param radius   the brush radius
     * @param material the block to build with; shape-only brushes such as {@link #SMOOTH} ignore it
     */
    public void apply(EditSession session, Vector position, int radius, BaseBlock material)
        throws MaxChangedBlocksException {
        brush.build(session, position, new BlockPattern(new BaseBlock(material)), radius);
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
