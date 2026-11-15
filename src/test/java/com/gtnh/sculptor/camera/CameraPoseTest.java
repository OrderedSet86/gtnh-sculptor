package com.gtnh.sculptor.camera;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

/**
 * The rules of {@link CameraPose#moveBackAndUp}, over poses from a seeded {@link Random} and over the boundary yaws 0
 * and 90.
 */
public class CameraPoseTest {

    private static final double EPSILON = 1.0E-9D;

    @Test
    public void theMovedPoseIsBehindAndAboveWithTheSameRotation() {
        final Random random = new Random(43L);
        for (int run = 0; run < 2000; run++) {
            final CameraPose pose = new CameraPose(
                random.nextDouble() * 2000.0D - 1000.0D,
                random.nextDouble() * 256.0D,
                random.nextDouble() * 2000.0D - 1000.0D,
                random.nextFloat() * 720.0F - 360.0F,
                random.nextFloat() * 180.0F - 90.0F);
            final double back = random.nextDouble() * 10.0D;
            final double up = random.nextDouble() * 10.0D;
            final CameraPose moved = pose.moveBackAndUp(back, up);
            final String where = pose + " back=" + back + " up=" + up;

            assertEquals(where, pose.yaw(), moved.yaw(), 0.0F);
            assertEquals(where, pose.pitch(), moved.pitch(), 0.0F);
            assertEquals(where, up, moved.y() - pose.y(), EPSILON);
            assertEquals(where, back, Math.hypot(moved.x() - pose.x(), moved.z() - pose.z()), EPSILON);

            // the horizontal look vector of EntityLivingBase.getLook() is (-sin yaw, cos yaw). The original pose is
            // `back` blocks ahead of the moved pose along that vector
            final double yawRadians = Math.toRadians(pose.yaw());
            final double ahead = (pose.x() - moved.x()) * -Math.sin(yawRadians)
                + (pose.z() - moved.z()) * Math.cos(yawRadians);
            assertEquals(where, back, ahead, EPSILON);
        }
    }

    @Test
    public void yawZeroFacesPositiveZAndYawNinetyFacesNegativeX() {
        final CameraPose south = new CameraPose(0.0D, 64.0D, 0.0D, 0.0F, 0.0F).moveBackAndUp(4.0D, 2.0D);
        assertEquals(0.0D, south.x(), EPSILON);
        assertEquals(66.0D, south.y(), EPSILON);
        assertEquals(-4.0D, south.z(), EPSILON);

        final CameraPose west = new CameraPose(0.0D, 64.0D, 0.0D, 90.0F, 0.0F).moveBackAndUp(4.0D, 2.0D);
        assertEquals(4.0D, west.x(), EPSILON);
        assertEquals(0.0D, west.z(), EPSILON);
    }
}
