package com.github.ceduz19.worstadvice.agent;

import com.github.ceduz19.worstadvice.InstrumentationHolder;

import java.lang.instrument.Instrumentation;

/** Java agent entrypoint used by the distribution fat JAR. */
public final class WorstAdviceAgent {

    private WorstAdviceAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        InstrumentationHolder.install(instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        InstrumentationHolder.install(instrumentation);
    }
}
