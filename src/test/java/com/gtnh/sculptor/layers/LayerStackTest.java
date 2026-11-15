package com.gtnh.sculptor.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.extension.input.InputParseException;

/** {@link LayerStack#parse} over {@code COUNT:BLOCK} tokens. The block parsers of the tests return stone. */
public class LayerStackTest {

    @Test
    public void theCountEndsAtTheFirstColon() throws Exception {
        final List<String> seen = new ArrayList<>();
        final LayerStack stack = LayerStack.parse(Arrays.asList("10:wool:14", "*:grass", "1:35:14"), rawBlock -> {
            seen.add(rawBlock);
            return new BaseBlock(1);
        });

        assertEquals(Arrays.asList("wool:14", "grass", "35:14"), seen);
        assertEquals(
            10,
            stack.layers()
                .get(0)
                .count());
        assertTrue(
            stack.layers()
                .get(1)
                .rest());
        assertFalse(
            stack.layers()
                .get(2)
                .rest());
        assertEquals(
            1,
            stack.layers()
                .get(2)
                .count());
    }

    @Test
    public void everyCountFromOneToTheLimitParses() throws Exception {
        final Random random = new Random(19L);
        final List<Integer> counts = new ArrayList<>(Arrays.asList(1, 999_999));
        for (int run = 0; run < 500; run++) counts.add(1 + random.nextInt(999_999));
        for (final int count : counts) {
            final LayerStack stack = LayerStack
                .parse(Collections.singletonList(count + ":stone"), rawBlock -> new BaseBlock(1));
            assertEquals(
                count,
                stack.layers()
                    .get(0)
                    .count());
        }
    }

    @Test
    public void malformedTokensAreRejectedWithTheirLangKey() {
        expectRejected("sculptor.layers.bad_token", "stone");
        expectRejected("sculptor.layers.bad_token", "x:stone");
        expectRejected("sculptor.layers.bad_token", "0:stone");
        expectRejected("sculptor.layers.bad_token", "-1:stone");
        expectRejected("sculptor.layers.bad_token", ":stone");
        expectRejected("sculptor.layers.bad_token", "3:");
        expectRejected("sculptor.layers.bad_token", "1000000:stone");
        expectRejected("sculptor.layers.two_rest", "*:stone", "2:dirt", "*:grass");
        expectRejected("sculptor.layers.unknown_block", "2:nope");
    }

    private static void expectRejected(String langKey, String... rawTokens) {
        try {
            LayerStack.parse(Arrays.asList(rawTokens), rawBlock -> {
                if (rawBlock.equals("nope")) throw new InputParseException("unknown block 'nope'");
                return new BaseBlock(1);
            });
            fail("accepted " + Arrays.toString(rawTokens));
        } catch (LayerSyntaxException exception) {
            assertEquals(Arrays.toString(rawTokens), langKey, exception.getLangKey());
        }
    }
}
