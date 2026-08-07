package com.github.ceduz19.worstadvice.bootstrap;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The only library class visible to transformed classes.
 *
 * <p>This class deliberately depends only on bootstrap JDK types. A private JAR
 * containing this single class is appended to the bootstrap class path before
 * any method is transformed.</p>
 */
public final class BootstrapBridge {

    private static final int ENTER = 0;
    private static final int EXIT = 1;
    private static final int EXCEPTION = 2;
    private static final ConcurrentMap<Long, Object[]> SITES = new ConcurrentHashMap<>();

    private BootstrapBridge() {
    }

    public static boolean register(
        long id,
        MethodHandle enter,
        MethodHandle exit,
        MethodHandle exception
    ) {
        Object[] callbacks = new Object[3];
        callbacks[ENTER] = enter;
        callbacks[EXIT] = exit;
        callbacks[EXCEPTION] = exception;

        return SITES.putIfAbsent(id, callbacks) == null;
    }

    public static void unregister(long id) {
        SITES.remove(id);
    }

    public static Object[] onEnter(long id, Object receiver, Object[] arguments) throws Throwable {
        Object[] callbacks = SITES.get(id);
        if (callbacks == null) return null;

        MethodHandle callback = (MethodHandle) callbacks[ENTER];
        if (callback == null) return null;

        return (Object[]) callback.invokeExact(receiver, arguments);
    }

    public static void onExit(long id, Object receiver, Object[] arguments, Object returnValue) throws Throwable {
        Object[] callbacks = SITES.get(id);
        if (callbacks == null) return;

        MethodHandle callback = (MethodHandle) callbacks[EXIT];
        if (callback != null) callback.invokeExact(receiver, arguments, returnValue);
    }

    public static Object[] onException(long id, Object receiver, Object[] arguments, Throwable thrown) throws Throwable {
        Object[] callbacks = SITES.get(id);
        if (callbacks == null) return null;

        MethodHandle callback = (MethodHandle) callbacks[EXCEPTION];
        if (callback == null) return null;

        return (Object[]) callback.invokeExact(receiver, arguments, thrown);
    }
}
