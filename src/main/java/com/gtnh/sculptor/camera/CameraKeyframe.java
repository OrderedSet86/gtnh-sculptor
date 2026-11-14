package com.gtnh.sculptor.camera;

import com.github.bsideup.jabel.Desugar;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

/**
 * One point on a camera path: the camera pose, and the speed tier of the segment that ends here.
 *
 * <p>
 * The speed is a {@link PathSpeed} tier, not a rate. The blocks per second of each tier are a setting in the panel, so
 * a saved path plays at the current rates of the tiers.
 * </p>
 */
@Desugar
public record CameraKeyframe(CameraPose pose, PathSpeed speed) {}
