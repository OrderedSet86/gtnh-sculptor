package com.gtnh.sculptor.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.sk89q.worldedit.blocks.BaseBlock;

/**
 * The rules of {@link LayerSettings}, over rows and edits from a seeded {@link Random} and over the boundary values:
 * one row, {@link LayerSettings#MAX_LAYERS} rows, count 1 and {@link LayerSettings#MAX_COUNT}, data 0 and 15, spill 0
 * and {@link LayerFill#MAX_SPILL}.
 */
public class LayerSettingsTest {

    private static final int CASES = 2000;
    private static final int MAX_BLOCK_ID = 32767;
    private static final int MAX_DATA = 15;
    // C01PacketChatMessage of 1.7.10 cuts a chat message at 100 characters
    private static final int CHAT_LIMIT = 100;

    @Test
    public void theCommandParsesBackToTheRows() throws Exception {
        final Random random = new Random(23L);
        for (int run = 0; run < CASES; run++) checkRoundTrip(generateSettings(random));

        checkRoundTrip(fillSettings(1, 1, 1, 0));
        checkRoundTrip(fillSettings(LayerSettings.MAX_LAYERS, LayerSettings.MAX_COUNT, MAX_BLOCK_ID, MAX_DATA));
        final LayerSettings fullSpill = fillSettings(2, 1, 1, 0);
        fullSpill.setSpill(LayerFill.MAX_SPILL);
        checkRoundTrip(fullSpill);
    }

    @Test
    public void theLongestCommandFitsInOneChatMessage() {
        final LayerSettings settings = fillSettings(
            LayerSettings.MAX_LAYERS,
            LayerSettings.MAX_COUNT,
            MAX_BLOCK_ID,
            MAX_DATA);
        settings.setSpill(LayerFill.MAX_SPILL);
        final String command = settings.buildCommand();
        assertTrue(command.length() + " characters: " + command, command.length() <= CHAT_LIMIT);
    }

    @Test
    public void editsKeepAtMostOneRestRowAndTheRowLimits() {
        final Random random = new Random(29L);
        final LayerSettings settings = new LayerSettings();
        for (int run = 0; run < CASES; run++) {
            final int row = random.nextInt(settings.getRowCount());
            switch (random.nextInt(4)) {
                case 0 -> settings.addRow();
                case 1 -> settings.removeRow(row);
                case 2 -> settings.toggleRest(row);
                default -> settings.setCount(row, random.nextInt(2 * LayerSettings.MAX_COUNT) - 10);
            }

            int restRows = 0;
            for (int index = 0; index < settings.getRowCount(); index++) {
                final LayerSettings.LayerRow layer = settings.getRow(index);
                if (layer.rest()) restRows++;
                assertTrue(
                    "count out of range: " + layer,
                    layer.count() >= 1 && layer.count() <= LayerSettings.MAX_COUNT);
            }
            assertTrue("rest rows: " + restRows, restRows <= 1);
            assertTrue(
                "row count: " + settings.getRowCount(),
                settings.getRowCount() >= 1 && settings.getRowCount() <= LayerSettings.MAX_LAYERS);
        }
    }

    // the command reads the tokens before `-s` as layers, and the token after `-s` as the spill
    private static void checkRoundTrip(LayerSettings settings) throws LayerSyntaxException {
        final String command = settings.buildCommand();
        final List<String> tokens = new ArrayList<>(Arrays.asList(command.split(" ")));
        assertEquals("/sclayers", tokens.remove(0));
        int spill = 0;
        final int flag = tokens.indexOf("-s");
        if (flag >= 0) {
            spill = Integer.parseInt(tokens.get(flag + 1));
            tokens.subList(flag, tokens.size())
                .clear();
        }

        final List<String> rawBlocks = new ArrayList<>();
        final LayerStack stack = LayerStack.parse(tokens, rawBlock -> {
            rawBlocks.add(rawBlock);
            return new BaseBlock(1);
        });

        assertEquals(command, settings.getSpill(), spill);
        assertEquals(
            command,
            settings.getRowCount(),
            stack.layers()
                .size());
        for (int index = 0; index < settings.getRowCount(); index++) {
            final LayerSettings.LayerRow row = settings.getRow(index);
            final LayerStack.Layer layer = stack.layers()
                .get(index);
            final String[] blockParts = rawBlocks.get(index)
                .split(":");
            assertEquals(command, row.rest(), layer.rest());
            if (!row.rest()) assertEquals(command, row.count(), layer.count());
            assertEquals(command, row.blockId(), Integer.parseInt(blockParts[0]));
            assertEquals(command, row.blockData(), (blockParts.length > 1) ? Integer.parseInt(blockParts[1]) : 0);
        }
    }

    private static LayerSettings generateSettings(Random random) {
        final LayerSettings settings = fillSettings(1 + random.nextInt(LayerSettings.MAX_LAYERS), 1, 1, 0);
        for (int index = 0; index < settings.getRowCount(); index++) {
            settings.setCount(index, 1 + random.nextInt(LayerSettings.MAX_COUNT));
            settings.setBlock(index, random.nextInt(MAX_BLOCK_ID + 1), random.nextInt(MAX_DATA + 1));
        }
        if (random.nextBoolean()) settings.toggleRest(random.nextInt(settings.getRowCount()));
        settings.setSpill(random.nextInt(LayerFill.MAX_SPILL + 1));
        return settings;
    }

    // `rowCount` rows of the same block and count, with no rest row and no spill
    private static LayerSettings fillSettings(int rowCount, int count, int blockId, int blockData) {
        final LayerSettings settings = new LayerSettings();
        while (settings.getRowCount() < rowCount) settings.addRow();
        while (settings.getRowCount() > rowCount) settings.removeRow(0);
        for (int index = 0; index < rowCount; index++) {
            if (settings.getRow(index)
                .rest()) settings.toggleRest(index);
            settings.setCount(index, count);
            settings.setBlock(index, blockId, blockData);
        }
        return settings;
    }
}
