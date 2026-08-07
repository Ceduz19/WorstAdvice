package com.github.ceduz19.worstadvice.api;

/** Advice invoked after a normal return from the original method body. */
@FunctionalInterface
public interface ExitAdvice {

    /** @param context invocation context @param returnValue boxed result, or null for void @throws Throwable from user advice */
    void onExit(AdviceContext context, Object returnValue) throws Throwable;
}
