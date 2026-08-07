package com.github.ceduz19.worstadvice.api;

import java.lang.reflect.Method;
import java.util.Objects;

/** Immutable context supplied to an advice callback. */
public final class AdviceContext {

    private final Method method;
    private final Object target;
    private final Object[] arguments;

    public AdviceContext(Method method, Object target, Object[] arguments) {
        this.method = Objects.requireNonNull(method, "method");
        this.target = target;
        this.arguments = Objects.requireNonNull(arguments, "arguments").clone();
    }

    /** The exact reflective method used to install the advice. */
    public Method method() {
        return method;
    }

    /** The receiver, or {@code null} for a static method. */
    public Object target() {
        return target;
    }

    /** A defensive copy of the arguments as they were on method entry. */
    public Object[] arguments() {
        return arguments.clone();
    }

    /** Returns one argument without allocating a copy of the full array. */
    public Object argument(int index) {
        return arguments[index];
    }

    /** Returns the number of arguments. */
    public int argumentCount() {
        return arguments.length;
    }
}
