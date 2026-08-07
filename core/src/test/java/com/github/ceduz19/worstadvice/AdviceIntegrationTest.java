package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.Advice;
import com.github.ceduz19.worstadvice.api.AdviceDecision;
import com.github.ceduz19.worstadvice.api.AdviceEngine;
import com.github.ceduz19.worstadvice.api.AdviceHandle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("try")
@Tag("javaagent")
class AdviceIntegrationTest {
    @Test
    void invokesEntryAndExitAndRestoresOriginalBytecode() throws Exception {
        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("sum", int.class, int.class);
        List<String> events = new ArrayList<>();

        try (AdviceEngine engine = WorstAdvice.create()) {
            AdviceHandle handle = engine.advise(method, Advice.builder()
                .onEnter(ctx -> {
                    assertSame(method, ctx.method());
                    assertSame(target, ctx.target());
                    assertArrayEquals(new Object[] {2, 5}, ctx.arguments());
                    events.add("enter");
                    return AdviceDecision.proceed();
                })
                .onExit((context, value) -> events.add("exit:" + value))
                .build()
            );

            assertEquals(7, method.invoke(target, 2, 5));
            assertEquals(Arrays.asList("enter", "exit:7"), events);
            events.clear();
            assertEquals(7, target.sum(2, 5));
            assertEquals(Arrays.asList("enter", "exit:7"), events);

            handle.close();
            assertFalse(handle.isActive());
            events.clear();
            assertEquals(7, target.sum(2, 5));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void entryCanShortCircuitValuesStaticMethodsAndVoidMethods() throws Exception {
        Target target = new Target();
        Method valueMethod = Target.class.getDeclaredMethod("increment", int.class);
        Method staticMethod = Target.class.getDeclaredMethod("staticLong", long.class);
        Method voidMethod = Target.class.getDeclaredMethod("touch");
        Method nullableMethod = Target.class.getDeclaredMethod("nullable");

        try (AdviceEngine engine = WorstAdvice.create();
             AdviceHandle value = engine.advise(valueMethod, Advice.builder()
                 .onEnter(context -> AdviceDecision.returnValue(91))
                 .build()
             );
             AdviceHandle fixedLong = engine.advise(staticMethod, Advice.builder()
                 .onEnter(context -> AdviceDecision.returnValue(123L))
                 .build()
             );
             AdviceHandle skippedVoid = engine.advise(voidMethod, Advice.builder()
                 .onEnter(context -> AdviceDecision.returnVoid())
                 .build()
             );
             AdviceHandle nullValue = engine.advise(nullableMethod, Advice.builder()
                 .onEnter(context -> AdviceDecision.returnValue(null))
                 .build()
             )
        ) {

            assertEquals(91, target.increment(4));
            assertEquals(0, target.executions);

            assertEquals(123L, Target.staticLong(9L));

            target.touch();
            assertEquals(0, target.executions);

            //noinspection DataFlowIssue
            assertNull(target.nullable());
            assertEquals(0, target.executions);
        }

        assertEquals(5, target.increment(4));
        assertEquals(10L, Target.staticLong(9L));
        target.touch();
        assertEquals("nullable", target.nullable());
        assertEquals(3, target.executions);
    }

    @Test
    void exceptionAdviceCanRecoverWithAValueOrVoid() throws Exception {
        Target target = new Target();
        Method valueMethod = Target.class.getDeclaredMethod("failValue");
        Method voidMethod = Target.class.getDeclaredMethod("failVoid");
        List<String> events = new ArrayList<>();

        try (AdviceEngine engine = WorstAdvice.create();
             AdviceHandle value = engine.advise(valueMethod, Advice.builder()
                 .onExit((context, result) -> events.add("exit"))
                 .onException((context, thrown) -> {
                     events.add(thrown.getMessage());
                     return AdviceDecision.returnValue("recovered");
                 })
                 .build()
             );
             AdviceHandle ignored = engine.advise(voidMethod, Advice.builder()
                 .onException((context, thrown) -> AdviceDecision.returnVoid())
                 .build()
             )
        ) {
            assertEquals("recovered", target.failValue());
            target.failVoid();
            assertEquals(Collections.singletonList("boom"), events);
        }
    }

    @Test
    void proceedFromExceptionAdviceRethrowsOriginalThrowable() throws Exception {
        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("failValue");

        try (AdviceEngine engine = WorstAdvice.create();
             AdviceHandle ignored = engine.advise(method, Advice.builder()
                 .onException((context, thrown) -> AdviceDecision.proceed())
                 .build()
             )
        ) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, target::failValue);
            assertEquals("boom", thrown.getMessage());
        }
    }

    static final class Target {
        private int executions;

        int sum(int left, int right) {
            return left + right;
        }

        int increment(int value) {
            executions++;
            return value + 1;
        }

        static long staticLong(long value) {
            return value + 1L;
        }

        void touch() {
            executions++;
        }

        String nullable() {
            executions++;
            return "nullable";
        }

        String failValue() {
            throw new IllegalStateException("boom");
        }

        void failVoid() {
            throw new IllegalArgumentException("void boom");
        }
    }
}
