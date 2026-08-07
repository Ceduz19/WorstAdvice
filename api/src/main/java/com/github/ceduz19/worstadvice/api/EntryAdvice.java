package com.github.ceduz19.worstadvice.api;

/** Advice invoked before the original method body. */
@FunctionalInterface
public interface EntryAdvice {

    /** @param context invocation context @return whether to proceed or return @throws Throwable from user advice */
    AdviceDecision onEnter(AdviceContext context) throws Throwable;
}
