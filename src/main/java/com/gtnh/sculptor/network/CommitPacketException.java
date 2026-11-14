package com.gtnh.sculptor.network;

/** A commit packet that {@link CommitRequest#decode} cannot read, or whose values are out of range. */
public final class CommitPacketException extends Exception {

    private static final long serialVersionUID = 1L;

    public CommitPacketException(String message) {
        super(message);
    }
}
