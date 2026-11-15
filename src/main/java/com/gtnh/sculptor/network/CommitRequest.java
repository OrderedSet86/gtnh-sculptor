package com.gtnh.sculptor.network;

import java.nio.charset.StandardCharsets;

import com.github.bsideup.jabel.Desugar;
import com.gtnh.sculptor.brush.BrushParams;
import com.gtnh.sculptor.brush.OverhangMode;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.gtnh.sculptor.edit.PlacementMask;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;

import io.netty.buffer.ByteBuf;

/**
 * A brush commit from a client: the brush, the target block, the settings and the placement mask.
 *
 * <p>
 * The packet has the enum constants by name, so a reorder of the constants does not change the meaning of a packet. The
 * server rejects a commit with an unknown name:
 * </p>
 *
 * <pre>
 *     name    brush       e.g. 6 "SPHERE": one length byte, then ASCII
 *     int     x, y, z
 *     int     radius
 *     short   material id
 *     byte    material data
 *     short   replace block id
 *     byte    replace block data
 *     short   secondary
 *     byte    strength in hundredths
 *     byte    auto-smooth in hundredths
 *     byte    use selected material, 0 or 1
 *     name    placement mask
 *     name    overhang mode
 * </pre>
 */
@Desugar
public record CommitRequest(SculptorBrush brush, Vector target, BrushParams params, PlacementMask mask) {

    public static final int MAX_RADIUS = 64;
    public static final int MAX_SECONDARY = 64;
    private static final int FIXED_BYTES = 12 + 4 + 2 + 1 + 2 + 1 + 2 + 1 + 1 + 1;
    private static final double HUNDREDTHS = 100.0D;

    /** Write the commit in the layout of the class javadoc. */
    public void encode(ByteBuf buf) {
        writeName(buf, brush);
        buf.writeInt(target.getBlockX());
        buf.writeInt(target.getBlockY());
        buf.writeInt(target.getBlockZ());
        buf.writeInt(params.radius());
        buf.writeShort(
            params.material()
                .getType());
        buf.writeByte(
            params.material()
                .getData());
        buf.writeShort(
            params.replaceBlock()
                .getType());
        buf.writeByte(
            params.replaceBlock()
                .getData());
        buf.writeShort(params.secondary());
        // strength and auto-smooth are from 0 to 1. The panel sliders are coarser than hundredths
        buf.writeByte((int) Math.round(params.strength() * HUNDREDTHS));
        buf.writeByte((int) Math.round(params.autoSmooth() * HUNDREDTHS));
        buf.writeByte(params.useSelectedMaterial() ? 1 : 0);
        writeName(buf, mask);
        writeName(buf, params.overhang());
    }

    /**
     * Read a commit from the bytes after the op code, and check the radius and the second parameter. The caller checks
     * the distance from the player.
     */
    public static CommitRequest decode(ByteBuf buf) throws CommitPacketException {
        final SculptorBrush brush = parseName(SculptorBrush.class, readName(buf));
        if (buf.readableBytes() < FIXED_BYTES) {
            throw new CommitPacketException("short commit (expected " + FIXED_BYTES + " bytes after the brush name)");
        }

        final Vector target = new Vector(buf.readInt(), buf.readInt(), buf.readInt());
        final int radius = buf.readInt();
        final int materialId = buf.readShort() & 0xFFFF;
        final int materialData = buf.readByte() & 0xF;
        final int replaceId = buf.readShort() & 0xFFFF;
        final int replaceData = buf.readByte() & 0xF;
        final int secondary = buf.readShort();
        final double strength = clamp01((buf.readByte() & 0xFF) / HUNDREDTHS);
        final double autoSmooth = clamp01((buf.readByte() & 0xFF) / HUNDREDTHS);
        final boolean useSelectedMaterial = buf.readByte() != 0;
        final PlacementMask mask = parseName(PlacementMask.class, readName(buf));
        final OverhangMode overhang = parseName(OverhangMode.class, readName(buf));

        if (radius < 1 || radius > MAX_RADIUS) {
            throw new CommitPacketException(
                "bad radius " + radius + " (expected a whole number from 1 to " + MAX_RADIUS + ")");
        }
        if (secondary < 0 || secondary > MAX_SECONDARY) {
            throw new CommitPacketException(
                "bad secondary " + secondary + " (expected a whole number from 0 to " + MAX_SECONDARY + ")");
        }

        final BrushParams params = BrushParams.builder()
            .radius(radius)
            .material(new BaseBlock(materialId, materialData))
            .secondary(secondary)
            .strength(strength)
            .autoSmooth(autoSmooth)
            .useSelectedMaterial(useSelectedMaterial)
            .overhang(overhang)
            .replaceBlock(new BaseBlock(replaceId, replaceData))
            .build();
        return new CommitRequest(brush, target, params, mask);
    }

    private static void writeName(ByteBuf buf, Enum<?> constant) {
        final byte[] name = constant.name()
            .getBytes(StandardCharsets.US_ASCII);
        buf.writeByte(name.length);
        buf.writeBytes(name);
    }

    private static String readName(ByteBuf buf) throws CommitPacketException {
        if (buf.readableBytes() < 1) throw new CommitPacketException("short commit (expected a name length byte)");
        final int length = buf.readUnsignedByte();
        if (buf.readableBytes() < length) {
            throw new CommitPacketException("short commit (expected a name of " + length + " bytes)");
        }

        final byte[] name = new byte[length];
        buf.readBytes(name);
        return new String(name, StandardCharsets.US_ASCII);
    }

    private static <E extends Enum<E>> E parseName(Class<E> type, String rawName) throws CommitPacketException {
        for (E constant : type.getEnumConstants()) {
            if (constant.name()
                .equals(rawName)) {
                return constant;
            }
        }
        throw new CommitPacketException(
            "unknown " + type.getSimpleName() + " '" + rawName + "' (expected a constant name such as SPHERE)");
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
