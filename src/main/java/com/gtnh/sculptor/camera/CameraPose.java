package com.gtnh.sculptor.camera;

import com.github.bsideup.jabel.Desugar;

/** A camera position and view direction. {@code yaw} and {@code pitch} are in degrees, as on a Minecraft entity. */
@Desugar
public record CameraPose(double x, double y, double z, float yaw, float pitch) {}
