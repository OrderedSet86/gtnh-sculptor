package com.gtnh.sculptor.layers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.github.bsideup.jabel.Desugar;
import com.google.common.collect.ImmutableList;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.extension.input.InputParseException;

/**
 * The layers of a {@code /sclayers} command, top to bottom. At most one layer is the rest layer, written {@code *}. The
 * rest layer fills the rows between the layers above and the layers below.
 *
 * <pre>
 *     10:stone    ten rows of stone
 *     *:grass     every row the other layers leave
 *     10:wool:14  ten rows of red wool. The count ends at the first ':'
 * </pre>
 */
@Desugar
public record LayerStack(List<Layer> layers) {

    public LayerStack {
        layers = ImmutableList.copyOf(layers);
    }

    /**
     * Parse each token as {@code COUNT:BLOCK}, with a whole number above 0 or {@code *} for the count.
     *
     * @throws LayerSyntaxException on a token without a count, on a count that is not a whole number above 0, on a
     *                              second {@code *}, or on a block that {@code blockParser} rejects
     */
    public static LayerStack parse(List<String> rawTokens, BlockParser blockParser) throws LayerSyntaxException {
        final List<Layer> layers = new ArrayList<>(rawTokens.size());
        boolean hasRest = false;
        for (final String rawToken : rawTokens) {
            final int colon = rawToken.indexOf(':');
            if (colon <= 0 || colon == rawToken.length() - 1) {
                throw new LayerSyntaxException("sculptor.layers.bad_token", rawToken);
            }

            final String rawCount = rawToken.substring(0, colon);
            final boolean rest = rawCount.equals("*");
            if (rest && hasRest) throw new LayerSyntaxException("sculptor.layers.two_rest");
            hasRest |= rest;
            final int count = rest ? 0 : parseCount(rawCount, rawToken);

            final BaseBlock block;
            try {
                block = blockParser.parse(rawToken.substring(colon + 1));
            } catch (InputParseException exception) {
                final LayerSyntaxException failure = new LayerSyntaxException(
                    "sculptor.layers.unknown_block",
                    rawToken,
                    exception.getMessage());
                failure.initCause(exception);
                throw failure;
            }
            layers.add(new Layer(block, count, rest));
        }
        return new LayerStack(layers);
    }

    // matches:
    // `10`
    // rejects:
    // `-1`
    // `+3`
    // `1000000`
    private static final Pattern COUNT = Pattern.compile("[0-9]{1,6}");

    private static int parseCount(String rawCount, String rawToken) throws LayerSyntaxException {
        if (!COUNT.matcher(rawCount)
            .matches()) throw new LayerSyntaxException("sculptor.layers.bad_token", rawToken);
        final int count = Integer.parseInt(rawCount);
        if (count == 0) throw new LayerSyntaxException("sculptor.layers.bad_token", rawToken);
        return count;
    }

    /** One layer. {@code count} is 0 on the rest layer. */
    @Desugar
    public record Layer(BaseBlock block, int count, boolean rest) {}

    /** Parse the block of a layer, such as {@code stone} or {@code 35:14}. */
    @FunctionalInterface
    public interface BlockParser {

        BaseBlock parse(String rawBlock) throws InputParseException;
    }
}
