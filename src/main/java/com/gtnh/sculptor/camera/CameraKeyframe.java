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

package com.gtnh.sculptor.camera;

import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * One point on a camera path: where the camera is, which way it faces, and how quickly it should
 * arrive there.
 *
 * <p>
 * Top-level and immutable so it can be written to disk and handed back. The speed is a tier rather
 * than a rate: the blocks-per-second behind each tier is editable in the panel, so a saved path
 * picks up whatever the tiers mean today instead of freezing the numbers it was recorded with.
 * </p>
 */
public final class CameraKeyframe {

    public final double x, y, z;
    public final float yaw, pitch;

    /** How fast to travel <em>into</em> this keyframe from the one before it. */
    public final PathSpeed speed;

    public CameraKeyframe(double x, double y, double z, float yaw, float pitch, PathSpeed speed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.speed = (speed == null) ? PathSpeed.NORMAL : speed;
    }
}
