package com.gtnh.sculptor.layers;

/** A {@code /sclayers} argument that does not parse, as a lang key and the values of the key. */
public class LayerSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Object[] formatValues;

    public LayerSyntaxException(String langKey, Object... formatValues) {
        super(langKey);
        this.formatValues = formatValues.clone();
    }

    public String getLangKey() {
        return getMessage();
    }

    public Object[] getFormatValues() {
        return formatValues.clone();
    }
}
