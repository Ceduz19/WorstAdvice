package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.AdviceEngine;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

/** Entry point for creating advice engines. */
public final class WorstAdvice {

    private WorstAdvice() {
    }

    /**
     * Creates an engine using the available {@link Instrumentation}, dynamically
     * attaching the bundled agent to the current JVM when necessary.
     *
     * @return a new advice engine
     */
    public static AdviceEngine create() {
        Instrumentation instrumentation = InstrumentationHolder.get();

        // JVM not initialized with -javaagent
        if (instrumentation == null) instrumentation = SelfAttachment.install();

        return create(instrumentation);
    }

    /** Creates an engine using an Instrumentation supplied by an embedding host. */
    public static AdviceEngine create(Instrumentation instrumentation) {
        Instrumentation value = Objects.requireNonNull(instrumentation, "instrumentation");
        InstrumentationHolder.install(value);
        return new AdviceEngineImpl(value);
    }
}
