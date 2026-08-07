package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.Advice;
import com.github.ceduz19.worstadvice.api.AdviceDecision;
import com.github.ceduz19.worstadvice.api.AdviceEngine;
import com.github.ceduz19.worstadvice.api.AdviceHandle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("try")
class SelfAttachmentTest {

    @Test
    void createDynamicallyAttachesWhenInstrumentationIsMissing() throws Exception {
        assertNull(InstrumentationHolder.get());

        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("increment", int.class);

        try (AdviceEngine engine = WorstAdvice.create();
             AdviceHandle ignored = engine.advise(method, Advice.builder()
                 .onEnter(context -> AdviceDecision.returnValue(42))
                 .build()
             )
        ) {
            assertEquals(42, target.increment(5));
        }

        assertEquals(6, target.increment(5));
    }

    static final class Target {
        int increment(int value) {
            return value + 1;
        }
    }
}
