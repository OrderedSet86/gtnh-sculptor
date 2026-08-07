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

package com.gtnh.sculptor.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.ChatComponentText;

import com.gtnh.sculptor.brush.BrushParams;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.gtnh.sculptor.edit.PlacementMask;
import com.gtnh.sculptor.edit.SculptorEditContext;
import com.gtnh.sculptor.edit.SculptorHistory;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
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
 * Carries brush commits and undo requests from the client to the server.
 *
 * <p>
 * The client previews against its own copy of the world, but the server owns the blocks -- true
 * even in singleplayer, where an integrated server is still running. So a commit is a request,
 * not a write: the client sends which brush it used and how it was configured, and the server
 * replays it against a real {@link EditSession}.
 * </p>
 *
 * <p>
 * The applied session is recorded in {@link SculptorHistory} rather than WorldEdit's
 * {@code LocalSession}, so brush strokes and {@code //} commands keep separate undo trails.
 * </p>
 *
 * <p>
 * Packets arrive on a netty thread. World modification happens on the server thread, so work is
 * queued here and drained on the next server tick rather than applied inline.
 * </p>
 */
public class SculptorNetwork {

    public static final String CHANNEL_NAME = "Sculptor";

    private static final byte OP_COMMIT = 1;
    private static final byte OP_UNDO = 2;
    private static final byte OP_REDO = 3;

    /** brush + xyz + radius + material + secondary + strength + autoSmooth + flags + mask. */
    private static final int COMMIT_PAYLOAD_BYTES = 1 + 12 + 4 + 3 + 2 + 1 + 1 + 1 + 1;

    /** Permission required to apply a brush. */
    private static final String PERMISSION_BRUSH = "sculptor.brush";

    /** Brush radius is clamped server-side; a client is not trusted to bound its own edits. */
    private static final int MAX_RADIUS = 64;

    /** Upper bound for a brush's second parameter, whatever that brush means by it. */
    private static final int MAX_SECONDARY = 64;

    /**
     * How far from the player a commit may land, in blocks.
     *
     * <p>
     * The client brush reaches 160 blocks, so this has to allow that, but it should not allow
     * arbitrary edits anywhere in the world from a crafted packet. Squared to avoid a square
     * root per commit.
     * </p>
     */
    private static final double MAX_COMMIT_DISTANCE_SQ = 192.0D * 192.0D;

    private static FMLEventChannel channel;

    private static final Queue<Runnable> pending = new ConcurrentLinkedQueue<Runnable>();

    public static void init() {
        SculptorNetwork handler = new SculptorNetwork();
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL_NAME);
        // Packet events come from the channel; the tick we drain on comes from the FML bus.
        channel.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    // ------------------------------------------------------------------ client side

    public static void sendCommit(SculptorBrush brush, int x, int y, int z, BrushParams params, PlacementMask mask) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(OP_COMMIT);
        buf.writeByte(brush.ordinal());
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(params.radius);
        buf.writeShort(params.material.getType());
        buf.writeByte(params.material.getData());
        buf.writeShort(params.secondary);
        // Strength and auto-smooth are 0..1; a byte of hundredths is finer than the controls
        // that produce them.
        buf.writeByte((int) Math.round(params.strength * 100.0D));
        buf.writeByte((int) Math.round(params.autoSmooth * 100.0D));
        buf.writeByte(params.useSelectedMaterial ? 1 : 0);
        buf.writeByte(mask.ordinal());

        channel.sendToServer(new FMLProxyPacket(buf, CHANNEL_NAME));
    }

    public static void sendUndo() {
        sendBare(OP_UNDO);
    }

    public static void sendRedo() {
        sendBare(OP_REDO);
    }

    private static void sendBare(byte op) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(op);
        channel.sendToServer(new FMLProxyPacket(buf, CHANNEL_NAME));
    }

    // ------------------------------------------------------------------ server side

    @SubscribeEvent
    public void onServerPacket(ServerCustomPacketEvent event) {
        if (!CHANNEL_NAME.equals(event.packet.channel())) return;

        ByteBuf buf = event.packet.payload();
        if (buf.readableBytes() < 1) return;

        final EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
        if (player == null) return;

        byte op = buf.readByte();
        switch (op) {
            case OP_COMMIT:
                readCommit(buf, player);
                break;
            case OP_UNDO:
                pending.add(new Runnable() {

                    @Override
                    public void run() {
                        applyHistory(player, true);
                    }
                });
                break;
            case OP_REDO:
                pending.add(new Runnable() {

                    @Override
                    public void run() {
                        applyHistory(player, false);
                    }
                });
                break;
            default:
                break;
        }
    }

    private static void readCommit(ByteBuf buf, final EntityPlayerMP player) {
        if (buf.readableBytes() < COMMIT_PAYLOAD_BYTES) return;

        final SculptorBrush brush = SculptorBrush.fromOrdinal(buf.readByte());
        final int x = buf.readInt();
        final int y = buf.readInt();
        final int z = buf.readInt();
        int radius = buf.readInt();
        int materialId = buf.readShort() & 0xFFFF;
        int materialData = buf.readByte() & 0xF;
        int secondary = buf.readShort();
        double strength = clamp01((buf.readByte() & 0xFF) / 100.0D);
        double autoSmooth = clamp01((buf.readByte() & 0xFF) / 100.0D);
        boolean useSelectedMaterial = buf.readByte() != 0;
        final PlacementMask mask = maskFromOrdinal(buf.readByte());

        if (radius < 1 || radius > MAX_RADIUS) return;
        if (secondary < 0 || secondary > MAX_SECONDARY) return;

        double dx = player.posX - (x + 0.5D);
        double dy = player.posY - (y + 0.5D);
        double dz = player.posZ - (z + 0.5D);
        if (dx * dx + dy * dy + dz * dz > MAX_COMMIT_DISTANCE_SQ) return;

        final BrushParams params = new BrushParams(
            radius,
            new BaseBlock(materialId, materialData),
            secondary,
            strength,
            autoSmooth,
            useSelectedMaterial);

        pending.add(new Runnable() {

            @Override
            public void run() {
                applyCommit(player, brush, new Vector(x, y, z), params, mask);
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Runnable task;
        while ((task = pending.poll()) != null) {
            task.run();
        }
    }

    private static void applyCommit(EntityPlayerMP playerMP, SculptorBrush brush, Vector centre, BrushParams params,
        PlacementMask mask) {
        try {
            ForgePlayer player = ForgeWorldEdit.inst.wrap(playerMP);

            // A packet is a request from an untrusted client, so authorise it here rather than
            // relying on the sender having the mod's UI. WorldEdit's own provider resolves this
            // to op-or-creative by default, and to a real permission node if the server has a
            // permissions mod, so Sculptor inherits whatever policy is already configured.
            if (!player.hasPermission(PERMISSION_BRUSH)) {
                tell(playerMP, "Sculptor: you do not have permission to edit");
                return;
            }

            // Still built through LocalSession so the player's //gmask and block bag apply --
            // only the history coupling is dropped.
            LocalSession session = ForgeWorldEdit.inst.getSession(playerMP);
            EditSession edit = session.createEditSession(player);

            SculptorEditContext ctx = SculptorEditContext.commit(edit);
            ctx.setPlacementMask(mask);

            try {
                brush.apply(ctx, centre, params);
            } finally {
                edit.flushQueue();
            }

            SculptorHistory.forPlayer(playerMP.getUniqueID())
                .remember(edit);

            tell(playerMP, "Sculptor: " + brush.label() + " applied " + edit.getBlockChangeCount() + " blocks");
        } catch (Exception e) {
            tell(playerMP, "Sculptor: commit failed: " + e);
            e.printStackTrace();
        }
    }

    private static void applyHistory(EntityPlayerMP playerMP, boolean undo) {
        try {
            ForgePlayer player = ForgeWorldEdit.inst.wrap(playerMP);
            if (!player.hasPermission(PERMISSION_BRUSH)) {
                tell(playerMP, "Sculptor: you do not have permission to edit");
                return;
            }

            SculptorHistory history = SculptorHistory.forPlayer(playerMP.getUniqueID());
            boolean done = undo ? history.undo(player) : history.redo(player);

            if (done) {
                tell(playerMP, undo ? "Sculptor: undone" : "Sculptor: redone");
            } else {
                tell(playerMP, undo ? "Sculptor: nothing to undo" : "Sculptor: nothing to redo");
            }
        } catch (Exception e) {
            tell(playerMP, "Sculptor: " + (undo ? "undo" : "redo") + " failed: " + e);
            e.printStackTrace();
        }
    }

    private static PlacementMask maskFromOrdinal(int ordinal) {
        PlacementMask[] all = PlacementMask.values();
        return (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : PlacementMask.OVERWRITE;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static void tell(EntityPlayerMP player, String message) {
        player.addChatMessage(new ChatComponentText(message));
    }

}
