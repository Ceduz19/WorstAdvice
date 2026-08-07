package com.github.ceduz19.worstadvice;

import java.lang.reflect.Method;

/** Runs in a short-lived helper JVM so attaching does not require allowAttachSelf. */
public final class ExternalAttacher {

    private ExternalAttacher() {
    }

    /**
     * Attaches the supplied agent JAR to the requested JVM.
     *
     * @param arguments target PID followed by agent JAR path
     * @throws Exception when the Attach API cannot load the agent
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected target PID and agent JAR path");

        Class<?> virtualMachineType = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = virtualMachineType.getMethod("attach", String.class);
        Method loadAgent = virtualMachineType.getMethod("loadAgent", String.class);
        Method detach = virtualMachineType.getMethod("detach");

        Object virtualMachine = attach.invoke(null, arguments[0]);
        try {
            loadAgent.invoke(virtualMachine, arguments[1]);
        } finally {
            detach.invoke(virtualMachine);
        }
    }
}
