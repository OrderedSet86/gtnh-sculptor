package com.gtnh.sculptor.camera;

import com.github.bsideup.jabel.Desugar;

/** A camera position and view direction. {@code yaw} and {@code pitch} are in degrees, as on a Minecraft entity. */
@Desugar
public record CameraPose(double x, double y, double z, float yaw, float pitch) {

    /**
     * Move the pose back along the horizontal view direction and up, with the same yaw and pitch. The horizontal part
     * of the view direction that {@code EntityLivingBase.getLook()} returns is (-sin yaw, cos yaw), so the view at yaw
     * 0 is toward +Z.
     */
    public CameraPose moveBackAndUp(double blocksBack, double blocksUp) {
        final double yawRadians = Math.toRadians(yaw);
        return new CameraPose(
            x + Math.sin(yawRadians) * blocksBack,
            y + blocksUp,
            z - Math.cos(yawRadians) * blocksBack,
            yaw,
            pitch);
    }
}
