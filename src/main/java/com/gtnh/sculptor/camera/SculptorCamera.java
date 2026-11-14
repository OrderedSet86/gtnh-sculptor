package com.gtnh.sculptor.camera;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import com.mojang.authlib.GameProfile;

/**
 * A player-shaped entity that the renderer looks through in place of the player, for the free camera and for path
 * playback.
 *
 * <p>
 * Minecraft renders from {@code Minecraft.renderViewEntity}, which must be an {@code EntityLivingBase}.
 * {@link SculptorCameraController} sets that field to this entity. The entity is never added to the world and has no
 * collision. {@link SculptorCameraController} sets the position and the rotation of the entity.
 * </p>
 */
public class SculptorCamera extends EntityPlayer {

    private static final GameProfile PROFILE = new GameProfile(null, "SculptorCamera");

    public SculptorCamera(World world, CameraPose pose) {
        super(world, PROFILE);

        this.noClip = true;
        this.isImmuneToFire = true;
        this.setSize(0.1F, 0.1F);

        // `EntityRenderer.orientCamera()` puts the view at posY - (yOffset - 1.62), and `rayTrace()` starts at posY.
        // With any yOffset other than 1.62, the targeted block is not the block under the crosshair
        this.yOffset = 1.62F;

        teleportTo(pose);
    }

    /**
     * Place the camera and set the {@code prev*} fields to the same values. The renderer interpolates from the
     * {@code prev*} fields each frame. A teleport with no change to the {@code prev*} fields renders as a smear from
     * the previous position.
     */
    public void teleportTo(CameraPose pose) {
        this.posX = this.prevPosX = this.lastTickPosX = pose.x();
        this.posY = this.prevPosY = this.lastTickPosY = pose.y();
        this.posZ = this.prevPosZ = this.lastTickPosZ = pose.z();
        this.rotationYaw = this.prevRotationYaw = pose.yaw();
        this.rotationPitch = this.prevRotationPitch = pose.pitch();
        this.setPosition(pose.x(), pose.y(), pose.z());
    }

    /** Move the camera, and copy the current values to the {@code prev*} fields for partial-tick interpolation. */
    public void moveTo(CameraPose pose) {
        this.prevPosX = this.lastTickPosX = this.posX;
        this.prevPosY = this.lastTickPosY = this.posY;
        this.prevPosZ = this.lastTickPosZ = this.posZ;
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;

        this.posX = pose.x();
        this.posY = pose.y();
        this.posZ = pose.z();
        this.rotationYaw = pose.yaw();
        this.rotationPitch = pose.pitch();
        this.setPosition(pose.x(), pose.y(), pose.z());
    }

    @Override
    public boolean isEntityInvulnerable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isInvisibleToPlayer(EntityPlayer player) {
        return true;
    }

    // the abstract methods of EntityPlayer, which a camera does not use

    @Override
    public void addChatMessage(IChatComponent message) {}

    @Override
    public boolean canCommandSenderUseCommand(int permission, String command) {
        return false;
    }

    @Override
    public ChunkCoordinates getPlayerCoordinates() {
        return new ChunkCoordinates((int) this.posX, (int) this.posY, (int) this.posZ);
    }

}
