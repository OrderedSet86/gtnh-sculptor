package com.gtnh.sculptor.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentTranslation;

import com.gtnh.sculptor.SculptorChat;
import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.edit.SculptorHistory;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent.ServerCustomPacketEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The channel for brush commits from the client to the server.
 *
 * <p>
 * The blocks are server state, also in singleplayer. Singleplayer runs an integrated server. A commit is a
 * {@link CommitRequest}, and the server runs the brush through an {@link EditSession}. The session goes to
 * {@link SculptorHistory}, not to the {@code LocalSession} of WorldEdit, so brush strokes and {@code //} commands have
 * separate undo histories.
 * </p>
 */
public class SculptorNetwork {

    public static final String CHANNEL_NAME = "Sculptor";

    private static final byte OP_COMMIT = 1;
    private static final String PERMISSION_BRUSH = "sculptor.brush";

    // the brush of the client reaches 160 blocks. The limit stops a crafted packet from editing anywhere in the world
    private static final double MAX_COMMIT_DISTANCE_SQ = 192.0D * 192.0D;

    // set once in init()
    private static FMLEventChannel channel;

    // a packet arrives on a netty thread, and the world changes only on the server thread. onServerTick() drains this
    // queue
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    public static void init() {
        final SculptorNetwork handler = new SculptorNetwork();
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL_NAME);
        // the channel posts packet events, and the FML bus posts the server tick
        channel.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    public static void sendCommit(CommitRequest request) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeByte(OP_COMMIT);
        request.encode(buf);
        channel.sendToServer(new FMLProxyPacket(buf, CHANNEL_NAME));
    }

    /**
     * Decode a commit packet on the netty thread, check the distance from the player, and queue the commit for the next
     * server tick. A packet that fails the checks is dropped, and a decode failure is logged at WARN.
     */
    @SubscribeEvent
    public void onServerPacket(ServerCustomPacketEvent event) {
        if (!CHANNEL_NAME.equals(event.packet.channel())) return;

        final ByteBuf buf = event.packet.payload();
        if (buf.readableBytes() < 1) return;

        final EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
        if (player == null) return;

        if (buf.readByte() != OP_COMMIT) return;

        final CommitRequest request;
        try {
            request = CommitRequest.decode(buf);
        } catch (CommitPacketException exception) {
            SculptorMod.LOG
                .warn("commit.rejected player={} reason={}", player.getCommandSenderName(), exception.getMessage());
            return;
        }

        final double dx = player.posX - (request.target()
            .getBlockX() + 0.5D);
        final double dy = player.posY - (request.target()
            .getBlockY() + 0.5D);
        final double dz = player.posZ - (request.target()
            .getBlockZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz > MAX_COMMIT_DISTANCE_SQ) return;

        PENDING.add(() -> applyCommit(player, request));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Runnable task;
        while ((task = PENDING.poll()) != null) {
            task.run();
        }
    }

    private static void applyCommit(EntityPlayerMP playerMP, CommitRequest request) {
        try {
            commitStroke(playerMP, request);
        } catch (MaxChangedBlocksException exception) {
            SculptorChat.sendTranslated(playerMP, "sculptor.commit.too_many_blocks", exception.getBlockLimit());
        } catch (RuntimeException exception) { // noqa: NAR142, a commit runs WorldEdit and tree generator code of
            // other mods in a server tick. FML rethrows an exception from a tick handler, which stops the server
            SculptorMod.LOG
                .warn("commit.failed player={} brush={}", playerMP.getCommandSenderName(), request.brush(), exception);
            SculptorChat.sendTranslated(playerMP, "sculptor.commit.failed", exception.toString());
        }
    }

    /**
     * Run the brush through an {@code EditSession} of the player, and record the session in the history of the player.
     * A failed stroke leaves the blocks written before the failure, and is not added to the history.
     */
    private static void commitStroke(EntityPlayerMP playerMP, CommitRequest request) throws MaxChangedBlocksException {
        final ForgePlayer player = ForgeWorldEdit.inst.wrap(playerMP);

        // the packet comes from an untrusted client. The permission provider of WorldEdit allows op or creative by
        // default, or the node of a permissions mod
        if (!player.hasPermission(PERMISSION_BRUSH)) {
            SculptorChat.sendTranslated(playerMP, "sculptor.command.no_permission");
            return;
        }

        // a LocalSession, so the //gmask and the block bag of the player apply
        final LocalSession session = ForgeWorldEdit.inst.getSession(playerMP);
        final EditSession edit = session.createEditSession(player);
        final SculptorEditContext ctx = SculptorEditContext.commit(edit);
        ctx.setPlacementMask(request.mask());

        try {
            request.brush()
                .apply(ctx, request.target(), request.params());
        } finally {
            edit.flushQueue();
        }

        SculptorHistory.forPlayer(playerMP.getUniqueID())
            .remember(edit);
        SculptorChat.sendTranslated(
            playerMP,
            "sculptor.commit.applied",
            new ChatComponentTranslation(
                request.brush()
                    .getLangKey()),
            edit.getBlockChangeCount());
    }

}
