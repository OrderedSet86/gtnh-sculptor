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

/**
 * Camera speeds, in blocks per second.
 *
 * <p>
 * Path speeds are expressed as a rate rather than a duration on purpose: a fixed time per
 * segment makes the camera crawl between close keyframes and rocket between distant ones, which
 * is the opposite of what you want when framing a shot. Holding the rate constant means a
 * segment takes as long as its length warrants, and the three tiers exist so a single path can
 * drift slowly through detail and then move on quickly.
 * </p>
 */
public final class CameraSettings {

    /** Which speed a path segment is travelled at. Chosen per keyframe. */
    public enum PathSpeed {

        SLOW("Slow", 4.0D),
        NORMAL("Normal", 10.0D),
        FAST("Fast", 25.0D);

        private final String label;
        private final double defaultBlocksPerSecond;

        PathSpeed(String label, double defaultBlocksPerSecond) {
            this.label = label;
            this.defaultBlocksPerSecond = defaultBlocksPerSecond;
        }

        public String label() {
            return label;
        }

        public double defaultBlocksPerSecond() {
            return defaultBlocksPerSecond;
        }
    }

    private static final CameraSettings INSTANCE = new CameraSettings();

    private final double[] pathSpeeds;

    /** Free-camera fly speed, in blocks per second. */
    private double flySpeed = 30.0D;

    /** Multiplier applied while the sprint key is held. */
    private double sprintMultiplier = 4.0D;

    private CameraSettings() {
        PathSpeed[] tiers = PathSpeed.values();
        pathSpeeds = new double[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            pathSpeeds[i] = tiers[i].defaultBlocksPerSecond();
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

    /** Zero would stall a path forever; the upper bound just keeps a typo from teleporting you. */
    private static double clampSpeed(double blocksPerSecond) {
        return Math.max(0.1D, Math.min(1000.0D, blocksPerSecond));
    }

}
