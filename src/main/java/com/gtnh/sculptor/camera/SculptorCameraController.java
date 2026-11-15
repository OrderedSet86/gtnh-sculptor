package com.gtnh.sculptor.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The free camera, and the recording and playback of keyframed camera paths.
 *
 * <p>
 * {@code attach()} sets {@code Minecraft.renderViewEntity} to a {@link SculptorCamera}. The real player still turns
 * with the mouse, and {@code applyFreeMovement()} copies the rotation of the player to the camera each tick. The
 * movement input of the player is replaced with a plain {@link MovementInput}. A plain {@link MovementInput} reads no
 * keys, so only the camera moves.
 * </p>
 */
public class SculptorCameraController {

    // `GameSettings` saves a binding as key_<description>:<code> and splits the line on ':'. A description with a colon
    // never matches on load, so use lang keys
    private static final String CATEGORY = "key.categories.sculptor";
    private static final KeyBinding TOGGLE_KEY = new KeyBinding("key.sculptor.camera.toggle", Keyboard.KEY_C, CATEGORY);
    private static final KeyBinding ADD_SLOW_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.slow",
        Keyboard.KEY_J,
        CATEGORY);
    private static final KeyBinding ADD_NORMAL_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.normal",
        Keyboard.KEY_K,
        CATEGORY);
    private static final KeyBinding ADD_FAST_KEY = new KeyBinding(
        "key.sculptor.camera.keyframe.fast",
        Keyboard.KEY_M,
        CATEGORY);
    private static final KeyBinding PLAY_KEY = new KeyBinding("key.sculptor.camera.play", Keyboard.KEY_P, CATEGORY);
    private static final KeyBinding CLEAR_KEY = new KeyBinding(
        "key.sculptor.camera.clear",
        Keyboard.KEY_DELETE,
        CATEGORY);

    private static final int TICKS_PER_SECOND = 20;
    private static final double START_BLOCKS_BACK = 4.0D;
    private static final double START_BLOCKS_UP = 2.0D;

    // set by the constructor. ClientProxy creates one controller, and the panel and /scpath get the path from
    // instance()
    private static SculptorCameraController instance;

    private final List<CameraKeyframe> path = new ArrayList<>();

    private SculptorCamera camera;
    private MovementInput savedInput;

    private boolean playing;
    private int playTick;
    private int[] segmentTicks;
    private int totalTicks;

    private String hudLine = "";
    private boolean hudPlaying;
    private int hudTick = -1;
    private int hudFlySpeed = -1;
    private int hudPathSize = -1;

    public SculptorCameraController() {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(ADD_SLOW_KEY);
        ClientRegistry.registerKeyBinding(ADD_NORMAL_KEY);
        ClientRegistry.registerKeyBinding(ADD_FAST_KEY);
        ClientRegistry.registerKeyBinding(PLAY_KEY);
        ClientRegistry.registerKeyBinding(CLEAR_KEY);

        instance = this;
    }

    /** The controller that {@code ClientProxy} creates, or {@code null} on a dedicated server. */
    public static SculptorCameraController instance() {
        return instance;
    }

    /** A copy of the path. */
    public List<CameraKeyframe> getPath() {
        return new ArrayList<>(path);
    }

    /**
     * Replace the path, and stop playback. {@code togglePlayback()} computes {@code segmentTicks} once, at the start of
     * playback.
     */
    public void setPath(List<CameraKeyframe> keyframes) {
        playing = false;
        path.clear();
        path.addAll(keyframes);
    }

    /**
     * Handle the camera keys: toggle the free camera, clear the path, add a keyframe of a speed tier, and play or stop.
     */
    @SubscribeEvent
    public void onKey(KeyInputEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (TOGGLE_KEY.isPressed()) {
            if (isActive()) {
                detach(mc);
            } else {
                attach(mc);
            }
        }

        if (CLEAR_KEY.isPressed()) {
            path.clear();
            playing = false;
            SculptorChat.sendTranslated(mc.thePlayer, "sculptor.camera.cleared");
        }

        if (ADD_SLOW_KEY.isPressed()) addKeyframe(mc, PathSpeed.SLOW);
        if (ADD_NORMAL_KEY.isPressed()) addKeyframe(mc, PathSpeed.NORMAL);
        if (ADD_FAST_KEY.isPressed()) addKeyframe(mc, PathSpeed.FAST);

        if (PLAY_KEY.isPressed()) {
            togglePlayback(mc);
        }
    }

    public boolean isActive() {
        return camera != null;
    }

    /**
     * Turn on the free camera {@link #START_BLOCKS_BACK} blocks behind and {@link #START_BLOCKS_UP} blocks above the
     * eye of the player, with the rotation of the player, and stop the movement input of the player. At the eye, the
     * view starts inside the head of the player model.
     */
    private void attach(Minecraft mc) {
        final EntityClientPlayerMP player = mc.thePlayer;

        // in 1.7.10 the posY of a player is at eye height. The bounding box hangs yOffset below posY
        camera = new SculptorCamera(
            mc.theWorld,
            new CameraPose(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch)
                .moveBackAndUp(START_BLOCKS_BACK, START_BLOCKS_UP));

        mc.renderViewEntity = camera;

        savedInput = player.movementInput;
        player.movementInput = new MovementInput();
        player.motionX = player.motionY = player.motionZ = 0.0D;

        SculptorChat.sendTranslated(
            player,
            "sculptor.camera.on",
            keyName(ADD_SLOW_KEY),
            keyName(ADD_NORMAL_KEY),
            keyName(ADD_FAST_KEY),
            keyName(PLAY_KEY),
            keyName(CLEAR_KEY));
    }

    private void detach(Minecraft mc) {
        final EntityClientPlayerMP player = mc.thePlayer;

        mc.renderViewEntity = player;
        if (savedInput != null) {
            player.movementInput = savedInput;
            savedInput = null;
        }
        camera = null;
        playing = false;

        SculptorChat.sendTranslated(player, "sculptor.camera.off");
    }

    private void addKeyframe(Minecraft mc, PathSpeed speed) {
        if (!isActive()) {
            SculptorChat.sendTranslated(mc.thePlayer, "sculptor.camera.inactive");
            return;
        }
        path.add(
            new CameraKeyframe(
                new CameraPose(camera.posX, camera.posY, camera.posZ, camera.rotationYaw, camera.rotationPitch),
                speed));
        SculptorChat.sendTranslated(
            mc.thePlayer,
            "sculptor.camera.keyframe_added",
            path.size(),
            StatCollector.translateToLocal(speed.getLangKey()));
    }

    private void togglePlayback(Minecraft mc) {
        if (!isActive()) {
            SculptorChat.sendTranslated(mc.thePlayer, "sculptor.camera.inactive");
            return;
        }
        if (playing) {
            stopPlayback(mc, "sculptor.camera.stopped");
            return;
        }
        if (path.size() < 2) {
            SculptorChat.sendTranslated(mc.thePlayer, "sculptor.camera.too_few_keyframes");
            return;
        }

        computeSegmentTimings();
        playing = true;
        playTick = 0;
        SculptorChat.sendTranslated(mc.thePlayer, "sculptor.camera.playing", path.size(), formatSeconds(totalTicks));
    }

    /**
     * Compute the ticks of each segment: the segment length over the speed of the keyframe at the end of the segment.
     * The camera then moves at a constant rate, not in a constant time per segment.
     */
    private void computeSegmentTimings() {
        final CameraSettings settings = CameraSettings.instance();
        final int segments = path.size() - 1;

        segmentTicks = new int[segments];
        totalTicks = 0;

        for (int i = 0; i < segments; i++) {
            final CameraPose from = path.get(i)
                .pose();
            final CameraKeyframe to = path.get(i + 1);

            final double dx = to.pose()
                .x() - from.x();
            final double dy = to.pose()
                .y() - from.y();
            final double dz = to.pose()
                .z() - from.z();
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            final double blocksPerSecond = settings.getPathSpeed(to.speed());
            // a segment with no travel still turns the camera, so the segment gets at least one tick
            final int ticks = (int) Math.max(1, Math.round(distance / blocksPerSecond * TICKS_PER_SECOND));

            segmentTicks[i] = ticks;
            totalTicks += ticks;
        }
    }

    /**
     * End playback, and give the player the final rotation of the camera. In free movement, the rotation of the camera
     * comes from the player, so without the copy the view jumps to the direction the player faced before playback.
     */
    private void stopPlayback(Minecraft mc, String langKey) {
        playing = false;
        if (camera != null && mc.thePlayer != null) {
            mc.thePlayer.rotationYaw = camera.rotationYaw;
            mc.thePlayer.rotationPitch = camera.rotationPitch;
            mc.thePlayer.prevRotationYaw = camera.rotationYaw;
            mc.thePlayer.prevRotationPitch = camera.rotationPitch;
        }
        SculptorChat.sendTranslated(mc.thePlayer, langKey);
    }

    /**
     * Move the camera each client tick: along the path during playback, and by the movement keys otherwise. The free
     * camera turns off when the world of the player changes.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isActive()) return;

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        // a dimension change or a disconnect replaces the world. The camera is in the previous world
        if (camera.worldObj != mc.theWorld) {
            detach(mc);
            return;
        }

        mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0.0D;
        syncViewState(mc);

        if (playing) {
            advancePlayback(mc);
        } else {
            applyFreeMovement(mc);
        }
    }

    /**
     * Copy the potion effects and the creative flag of the player to the camera, each tick.
     *
     * <p>
     * Parts of the renderer read {@code renderViewEntity}, not the player. {@code EntityRenderer.updateFogColor()}
     * checks night vision on the camera, {@code setupFog()} reads {@code capabilities.isCreativeMode} from the camera,
     * and the {@code CommonUniforms} of Angelica read the night vision of the camera for the shader uniform.
     * </p>
     *
     * <p>
     * A copy on attach is not enough. The camera is never ticked, so the duration of a copied effect does not count
     * down, and night vision flickers below 200 ticks left. {@code addPotionEffect()} merges with an effect the camera
     * has, so clear the effects first. {@code updateFogColor()} checks the camera and then reads the effect of the
     * player with no null check, so the camera must never have an effect that the player does not.
     * </p>
     */
    private void syncViewState(Minecraft mc) {
        camera.capabilities.isCreativeMode = mc.thePlayer.capabilities.isCreativeMode;

        camera.clearActivePotions();
        for (Object effect : mc.thePlayer.getActivePotionEffects()) {
            camera.addPotionEffect(new PotionEffect((PotionEffect) effect));
        }
    }

    /**
     * Move the free camera by the movement keys at the fly speed, times the sprint multiplier while sprint is down.
     * Forward and back follow the yaw and the pitch, strafe follows the yaw, and jump and sneak move straight up and
     * down.
     */
    private void applyFreeMovement(Minecraft mc) {
        final float yaw = mc.thePlayer.rotationYaw;
        final float pitch = mc.thePlayer.rotationPitch;

        double forward = 0.0D;
        double strafe = 0.0D;
        double vertical = 0.0D;

        if (mc.gameSettings.keyBindForward.getIsKeyPressed()) forward += 1.0D;
        if (mc.gameSettings.keyBindBack.getIsKeyPressed()) forward -= 1.0D;
        if (mc.gameSettings.keyBindLeft.getIsKeyPressed()) strafe += 1.0D;
        if (mc.gameSettings.keyBindRight.getIsKeyPressed()) strafe -= 1.0D;
        if (mc.gameSettings.keyBindJump.getIsKeyPressed()) vertical += 1.0D;
        if (mc.gameSettings.keyBindSneak.getIsKeyPressed()) vertical -= 1.0D;

        final CameraSettings settings = CameraSettings.instance();
        double speed = settings.getFlySpeed() / TICKS_PER_SECOND;
        if (mc.gameSettings.keyBindSprint.getIsKeyPressed()) {
            speed *= settings.getSprintMultiplier();
        }

        double x = camera.posX;
        double y = camera.posY;
        double z = camera.posZ;

        if (forward != 0.0D || strafe != 0.0D) {
            // forward follows the pitch too, so the camera flies in the view direction
            final double yawRad = Math.toRadians(yaw);
            final double pitchRad = Math.toRadians(pitch);

            final double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
            final double lookY = -Math.sin(pitchRad);
            final double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

            final double rightX = Math.cos(yawRad);
            final double rightZ = Math.sin(yawRad);

            x += (lookX * forward + rightX * strafe) * speed;
            y += (lookY * forward) * speed;
            z += (lookZ * forward + rightZ * strafe) * speed;
        }

        y += vertical * speed;

        camera.moveTo(new CameraPose(x, y, z, yaw, pitch));
    }

    /** Move the camera to the pose of the current tick of the path, and stop after the last tick. */
    private void advancePlayback(Minecraft mc) {
        if (playTick >= totalTicks) {
            stopPlayback(mc, "sculptor.camera.finished");
            return;
        }

        int segment = 0;
        int ticksIntoSegment = playTick;
        while (segment < segmentTicks.length - 1 && ticksIntoSegment >= segmentTicks[segment]) {
            ticksIntoSegment -= segmentTicks[segment];
            segment++;
        }

        final double t = ticksIntoSegment / (double) segmentTicks[segment];

        final CameraPose p1 = path.get(segment)
            .pose();
        final CameraPose p2 = path.get(Math.min(path.size() - 1, segment + 1))
            .pose();

        final double x = interpolateAxis(CameraPose::x, segment, t);
        final double y = interpolateAxis(CameraPose::y, segment, t);
        final double z = interpolateAxis(CameraPose::z, segment, t);

        // the spline overshoots between keyframes. In position the overshoot is a smooth arc, in yaw a wobble, so the
        // angles are linear along the shortest arc
        final float yaw = p1.yaw() + (float) (MathHelper.wrapAngleTo180_double(p2.yaw() - p1.yaw()) * t);
        final float pitch = p1.pitch() + (float) ((p2.pitch() - p1.pitch()) * t);

        final CameraPose pose = new CameraPose(x, y, z, yaw, pitch);
        if (playTick == 0) {
            // the move from the parked camera to the first keyframe is a cut. Interpolation would smear the cut over a
            // frame
            camera.teleportTo(pose);
        } else {
            camera.moveTo(pose);
        }
        playTick++;
    }

    /**
     * Interpolate one axis of the pose along a segment, by a Catmull-Rom spline from keyframe {@code segment} to the
     * next, shaped by the keyframes before and after the segment. The spline passes through every keyframe.
     */
    private double interpolateAxis(ToDoubleFunction<CameraPose> axis, int segment, double t) {
        final int last = path.size() - 1;
        final double p0 = axis.applyAsDouble(
            path.get(Math.max(0, segment - 1))
                .pose());
        final double p1 = axis.applyAsDouble(
            path.get(segment)
                .pose());
        final double p2 = axis.applyAsDouble(
            path.get(Math.min(last, segment + 1))
                .pose());
        final double p3 = axis.applyAsDouble(
            path.get(Math.min(last, segment + 2))
                .pose());
        final double t2 = t * t;
        final double t3 = t2 * t;
        return 0.5D * ((2.0D * p1) + (-p0 + p2) * t
            + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t2
            + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t3);
    }

    /** Report the HUD line of the free camera, or {@code null} when the free camera is off. */
    public String readHudLine() {
        if (!isActive()) return null;
        updateHudLine();
        return hudLine;
    }

    /** Rebuild the HUD text when a value in the text changes. The overlay event fires each frame. */
    private void updateHudLine() {
        final int flySpeed = (int) Math.round(
            CameraSettings.instance()
                .getFlySpeed());
        if (!(playing != hudPlaying || playTick != hudTick || flySpeed != hudFlySpeed || path.size() != hudPathSize)) {
            return;
        }

        hudPlaying = playing;
        hudTick = playTick;
        hudFlySpeed = flySpeed;
        hudPathSize = path.size();
        hudLine = playing
            ? StatCollector.translateToLocalFormatted(
                "sculptor.camera.hud.playing",
                formatSeconds(playTick),
                formatSeconds(totalTicks))
            : StatCollector.translateToLocalFormatted(
                "sculptor.camera.hud.free",
                flySpeed,
                path.size(),
                keyName(ADD_SLOW_KEY),
                keyName(ADD_NORMAL_KEY),
                keyName(ADD_FAST_KEY),
                keyName(PLAY_KEY),
                keyName(CLEAR_KEY));
    }

    private static String formatSeconds(int ticks) {
        return String.format("%.1f", ticks / (double) TICKS_PER_SECOND);
    }

    private static String keyName(KeyBinding key) {
        return GameSettings.getKeyDisplayString(key.getKeyCode());
    }

}
