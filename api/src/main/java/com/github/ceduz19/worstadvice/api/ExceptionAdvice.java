package com.github.ceduz19.worstadvice.api;

/** Advice invoked when the original method body exits by throwing. */
@FunctionalInterface
public interface ExceptionAdvice {

    /** @param context invocation context @param thrown original throwable @return rethrow or return decision @throws Throwable from user advice */
    AdviceDecision onException(AdviceContext context, Throwable thrown) throws Throwable;
}
