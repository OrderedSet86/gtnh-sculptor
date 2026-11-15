package com.gtnh.sculptor.layers;

import java.util.ArrayList;
import java.util.List;

import com.github.bsideup.jabel.Desugar;
import com.sk89q.worldedit.blocks.BlockID;

/**
 * The rows of the Layers page of the editor panel, top to bottom, and the height variation, with {@link #buildCommand}
 * for the {@code /sclayers} command of the rows.
 *
 * <pre>
 *     /sclayers 10:1 *:2 10:7 -s 3
 * </pre>
 */
public final class LayerSettings {

    // C01PacketChatMessage of 1.7.10 cuts a chat message at 100 characters. With 5 rows, counts up to MAX_COUNT and
    // block ids up to 32767, the longest command is 85 characters
    public static final int MAX_LAYERS = 5;
    public static final int MAX_COUNT = 9999;

    private final List<LayerRow> rows = new ArrayList<>();
    private int heightVariation;

    public LayerSettings() {
        rows.add(new LayerRow(BlockID.STONE, 0, 10, false));
        rows.add(new LayerRow(BlockID.GRASS, 0, 1, true));
        rows.add(new LayerRow(BlockID.BEDROCK, 0, 10, false));
    }

    public int getRowCount() {
        return rows.size();
    }

    public LayerRow getRow(int index) {
        return rows.get(index);
    }

    /** Add a row of 1 stone under the last row, up to {@link #MAX_LAYERS} rows. */
    public void addRow() {
        if (rows.size() < MAX_LAYERS) rows.add(new LayerRow(BlockID.STONE, 0, 1, false));
    }

    /** Remove the row, unless the row is the only row. */
    public void removeRow(int index) {
        if (rows.size() > 1) rows.remove(index);
    }

    /** Make the row the rest row, and clear the rest flag of the other rows. On the rest row, clear the flag. */
    public void toggleRest(int index) {
        final boolean rest = !rows.get(index)
            .rest();
        for (int row = 0; row < rows.size(); row++) {
            final LayerRow layer = rows.get(row);
            rows.set(row, new LayerRow(layer.blockId(), layer.blockData(), layer.count(), rest && row == index));
        }
    }

    public void setCount(int index, int count) {
        final LayerRow layer = rows.get(index);
        final int clamped = Math.max(1, Math.min(MAX_COUNT, count));
        rows.set(index, new LayerRow(layer.blockId(), layer.blockData(), clamped, layer.rest()));
    }

    public void setBlock(int index, int blockId, int blockData) {
        final LayerRow layer = rows.get(index);
        rows.set(index, new LayerRow(blockId, blockData, layer.count(), layer.rest()));
    }

    public int getHeightVariation() {
        return heightVariation;
    }

    public void setHeightVariation(int heightVariation) {
        this.heightVariation = Math.max(0, Math.min(LayerFill.MAX_HEIGHT_VARIATION, heightVariation));
    }

    /** Write the rows as a {@code /sclayers} command, with each block as {@code id} or {@code id:data}. */
    public String buildCommand() {
        final StringBuilder command = new StringBuilder("/sclayers");
        for (final LayerRow layer : rows) {
            command.append(' ')
                .append(layer.rest() ? "*" : Integer.toString(layer.count()))
                .append(':')
                .append(layer.blockId());
            if (layer.blockData() != 0) command.append(':')
                .append(layer.blockData());
        }
        if (heightVariation > 0) command.append(" -s ")
            .append(heightVariation);
        return command.toString();
    }

    /**
     * One row. {@link #toggleRest} leaves {@code count} unchanged, so after a second toggle of the rest flag the row
     * has the count again.
     */
    @Desugar
    public record LayerRow(int blockId, int blockData, int count, boolean rest) {}
}
