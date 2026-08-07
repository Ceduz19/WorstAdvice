package com.github.ceduz19.worstadvice.api;

/** A removable advice registration. */
public interface AdviceHandle extends AutoCloseable {

    /** Returns whether this registration is still active. */
    boolean isActive();

    /** Removes this registration and restores the method when it was the last one. */
    @Override
    void close();
}
