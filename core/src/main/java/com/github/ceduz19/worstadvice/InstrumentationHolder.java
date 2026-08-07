package com.github.ceduz19.worstadvice;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

/** Shared hand-off between the Java agent and the public factory. */
public final class InstrumentationHolder {

    private static volatile Instrumentation instrumentation;

    private InstrumentationHolder() {
    }

    public static synchronized void install(Instrumentation candidate) {
        Objects.requireNonNull(candidate, "instrumentation");
        if (instrumentation != null && instrumentation != candidate)
            throw new IllegalStateException("A different Instrumentation instance is already installed");

        instrumentation = candidate;
    }

    static Instrumentation get() {
        return instrumentation;
    }
}
