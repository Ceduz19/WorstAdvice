package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.Advice;
import com.github.ceduz19.worstadvice.api.AdviceContext;
import com.github.ceduz19.worstadvice.api.AdviceDecision;
import com.github.ceduz19.worstadvice.api.EntryAdvice;
import com.github.ceduz19.worstadvice.api.ExceptionAdvice;
import com.github.ceduz19.worstadvice.api.ExitAdvice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Adapts public callbacks to bootstrap-safe MethodHandle signatures. */
final class AdviceDispatcher {

    private static final MethodHandle ENTER_HANDLE;
    private static final MethodHandle EXIT_HANDLE;
    private static final MethodHandle EXCEPTION_HANDLE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ENTER_HANDLE = lookup.findVirtual(
                AdviceDispatcher.class,
                "enter",
                MethodType.methodType(Object[].class, Object.class, Object[].class)
            );
            EXIT_HANDLE = lookup.findVirtual(
                AdviceDispatcher.class,
                "exit",
                MethodType.methodType(Void.TYPE, Object.class, Object[].class, Object.class)
            );
            EXCEPTION_HANDLE = lookup.findVirtual(
                AdviceDispatcher.class,
                "exception",
                MethodType.methodType(Object[].class, Object.class, Object[].class, Throwable.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Method method;
    private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();

    AdviceDispatcher(Method method) {
        this.method = Objects.requireNonNull(method, "method");
    }

    Registration add(Advice advice) {
        Registration registration = new Registration(advice);
        registrations.add(registration);
        return registration;
    }

    int remove(Registration registration) {
        int index = registrations.indexOf(registration);
        if (index >= 0) registrations.remove(index);
        return index;
    }

    void restore(int index, Registration registration) {
        registrations.add(index, registration);
    }

    boolean isEmpty() {
        return registrations.isEmpty();
    }

    MethodHandle enterHandle() {
        return ENTER_HANDLE.bindTo(this);
    }

    MethodHandle exitHandle() {
        return EXIT_HANDLE.bindTo(this);
    }

    MethodHandle exceptionHandle() {
        return EXCEPTION_HANDLE.bindTo(this);
    }

    @SuppressWarnings("unused")
    private Object[] enter(Object receiver, Object[] arguments) throws Throwable {
        AdviceContext context = null;

        for (Registration registration : registrations) {
            EntryAdvice callback = registration.advice.entry();
            if (callback == null) continue;

            if (context == null) context = new AdviceContext(method, receiver, arguments);

            AdviceDecision decision = Objects.requireNonNull(callback.onEnter(context), "Entry advice returned null");
            if (decision.isReturn()) return new Object[] { decision.value() };
        }

        return null;
    }

    @SuppressWarnings("unused")
    private void exit(Object receiver, Object[] arguments, Object returnValue) throws Throwable {
        AdviceContext context = null;
        Registration[] snapshot = registrations.toArray(new Registration[0]);

        for (int index = snapshot.length - 1; index >= 0; index--) {
            ExitAdvice callback = snapshot[index].advice.exit();
            if (callback == null) continue;

            if (context == null) context = new AdviceContext(method, receiver, arguments);

            callback.onExit(context, returnValue);
        }
    }

    @SuppressWarnings("unused")
    private Object[] exception(Object receiver, Object[] arguments, Throwable thrown) throws Throwable {
        AdviceContext context = null;
        Registration[] snapshot = registrations.toArray(new Registration[0]);

        for (int index = snapshot.length - 1; index >= 0; index--) {
            ExceptionAdvice callback = snapshot[index].advice.exception();
            if (callback == null) continue;

            if (context == null) context = new AdviceContext(method, receiver, arguments);

            AdviceDecision decision = Objects.requireNonNull(callback.onException(context, thrown), "Exception advice returned null");
            if (decision.isReturn()) return new Object[] {decision.value()};
        }

        return null;
    }

    static final class Registration {

        private final Advice advice;

        private Registration(Advice advice) {
            this.advice = Objects.requireNonNull(advice, "advice");
        }
    }
}
