package com.github.ceduz19.worstadvice.api;

import java.lang.reflect.Method;

/** Installs and removes runtime advice on already-loaded methods. */
public interface AdviceEngine extends AutoCloseable {

    /**
     * Installs callbacks on an already-loaded method.
     * @param method exact method to transform
     * @param advice callback group
     * @return a removable registration
     */
    AdviceHandle advise(Method method, Advice advice);

    /** @return whether this engine has been closed */
    boolean isClosed();

    @Override
    void close();
}
