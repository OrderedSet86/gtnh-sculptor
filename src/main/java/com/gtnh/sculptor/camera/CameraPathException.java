package com.gtnh.sculptor.camera;

import java.util.Arrays;

/**
 * A camera path file that {@link CameraPathStore} cannot read or write.
 *
 * <p>
 * The exception has a lang key and the values for the key, for a message in the language of the player.
 * {@link #getMessage()} has the key and the values, for the log.
 * </p>
 */
public final class CameraPathException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String langKey;
    private final Object[] formatValues;

    public CameraPathException(String langKey, Object... formatValues) {
        super(langKey + " " + Arrays.toString(formatValues));
        this.langKey = langKey;
        this.formatValues = formatValues.clone();
    }

    public CameraPathException(Throwable cause, String langKey, Object... formatValues) {
        super(langKey + " " + Arrays.toString(formatValues), cause);
        this.langKey = langKey;
        this.formatValues = formatValues.clone();
    }

    public String getLangKey() {
        return langKey;
    }

    public Object[] getFormatValues() {
        return formatValues.clone();
    }
}
