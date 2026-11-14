package com.gtnh.sculptor.camera;

import java.util.Locale;

/**
 * The camera speeds on the client, in blocks per second.
 *
 * <p>
 * A path segment moves at the rate of a {@link PathSpeed} tier, not in a fixed time. With a fixed time per segment, the
 * camera would crawl between close keyframes and race between distant ones.
 * </p>
 */
public final class CameraSettings {

    private static final double MIN_SPEED = 0.1D;
    private static final double MAX_SPEED = 1000.0D;
    private static final CameraSettings INSTANCE = new CameraSettings();

    private final double[] pathSpeeds;
    private double flySpeed = 30.0D;
    private final double sprintMultiplier = 4.0D;

    /** The path name that the save and load buttons of the panel use. */
    private String pathName = "path1";

    private CameraSettings() {
        final PathSpeed[] tiers = PathSpeed.values();
        pathSpeeds = new double[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            pathSpeeds[i] = tiers[i].getDefaultBlocksPerSecond();
        }
    }

    public static CameraSettings instance() {
        return INSTANCE;
    }

    public double getPathSpeed(PathSpeed tier) {
        return pathSpeeds[tier.ordinal()];
    }

    public void setPathSpeed(PathSpeed tier, double blocksPerSecond) {
        pathSpeeds[tier.ordinal()] = clampSpeed(blocksPerSecond);
    }

    public double getFlySpeed() {
        return flySpeed;
    }

    public void setFlySpeed(double blocksPerSecond) {
        this.flySpeed = clampSpeed(blocksPerSecond);
    }

    public double getSprintMultiplier() {
        return sprintMultiplier;
    }

    public String getPathName() {
        return pathName;
    }

    public void setPathName(String name) {
        this.pathName = (name == null) ? "" : name.trim();
    }

    // a speed of 0 stops a path forever, and the upper bound stops a typo from sending the camera far away
    private static double clampSpeed(double blocksPerSecond) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, blocksPerSecond));
    }

    /** The speed tier of a path segment, set on the keyframe at the end of the segment. */
    public enum PathSpeed {

        SLOW(4.0D),
        NORMAL(10.0D),
        FAST(25.0D);

        private final double defaultBlocksPerSecond;

        PathSpeed(double defaultBlocksPerSecond) {
            this.defaultBlocksPerSecond = defaultBlocksPerSecond;
        }

        public double getDefaultBlocksPerSecond() {
            return defaultBlocksPerSecond;
        }

        public String getLangKey() {
            return "sculptor.camera.speed." + name().toLowerCase(Locale.ROOT);
        }
    }

}
