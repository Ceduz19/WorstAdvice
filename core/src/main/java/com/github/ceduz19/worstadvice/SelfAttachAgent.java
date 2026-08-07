package com.github.ceduz19.worstadvice;

import java.lang.instrument.Instrumentation;

/** Minimal agent entrypoint used by the runtime-generated self-attach JAR. */
public final class SelfAttachAgent {

    private static volatile Instrumentation instrumentation;

    private SelfAttachAgent() {
    }

    /**
     * Receives Instrumentation when this class is loaded through the Attach API.
     *
     * @param arguments ignored agent arguments
     * @param value Instrumentation supplied by the JVM
     */
    public static void agentmain(String arguments, Instrumentation value) {
        instrumentation = value;
    }

    /**
     * Returns the Instrumentation received by {@link #agentmain}.
     *
     * @return the installed Instrumentation, or {@code null}
     */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }
}
