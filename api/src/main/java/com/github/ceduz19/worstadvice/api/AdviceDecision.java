package com.github.ceduz19.worstadvice.api;

/**
 * The decision returned by entry and exception advice.
 * A return decision skips the original invocation (entry advice) or suppresses
 * the thrown {@link Throwable} (exception advice).
 */
public final class AdviceDecision {

    private static final AdviceDecision PROCEED = new AdviceDecision(false, null);
    private static final AdviceDecision RETURN_VOID = new AdviceDecision(true, null);

    private final boolean returns;
    private final Object value;

    private AdviceDecision(boolean returns, Object value) {
        this.returns = returns;
        this.value = value;
    }

    /** Continue the original method, or rethrow the original exception. */
    public static AdviceDecision proceed() {
        return PROCEED;
    }

    /** Return {@code value} from the advised method. {@code null} is supported. */
    public static AdviceDecision returnValue(Object value) {
        return value == null ? RETURN_VOID : new AdviceDecision(true, value);
    }

    /** Return immediately from a {@code void} method. */
    public static AdviceDecision returnVoid() {
        return RETURN_VOID;
    }

    /** Returns whether this decision replaces normal execution with a return. */
    public boolean isReturn() {
        return returns;
    }

    /** Returns the replacement value, possibly {@code null}. */
    public Object value() {
        return value;
    }
}
