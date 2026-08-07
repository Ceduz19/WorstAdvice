package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.Advice;
import com.github.ceduz19.worstadvice.api.AdviceEngine;
import com.github.ceduz19.worstadvice.api.AdviceHandle;
import com.github.ceduz19.worstadvice.AdviceDispatcher.Registration;
import com.github.ceduz19.worstadvice.TransformationPlan.AdviceSite;
import com.github.ceduz19.worstadvice.TransformationPlan.MethodKey;
import org.objectweb.asm.Type;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/** Default engine implementation backed by retransformation and ASM. */
final class AdviceEngineImpl implements AdviceEngine {

    private final Instrumentation instrumentation;
    private final BootstrapBridgeAccess bridge;
    private final ConcurrentMap<Class<?>, TransformationPlan> plans = new ConcurrentHashMap<>();
    private final AdviceClassFileTransformer transformer;
    private final Object lock = new Object();
    private final Set<HandleImpl> handles = new LinkedHashSet<>();
    private volatile boolean closed;

    public AdviceEngineImpl(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        if (!instrumentation.isRetransformClassesSupported())
            throw new IllegalArgumentException("Instrumentation does not support retransformation");

        bridge = BootstrapBridgeAccess.install(instrumentation);
        transformer = new AdviceClassFileTransformer(plans, bridge.internalName());
        instrumentation.addTransformer(transformer, true);
    }

    @Override
    public AdviceHandle advise(Method method, Advice advice) {
        Method targetMethod = Objects.requireNonNull(method, "method");
        Advice callbacks = Objects.requireNonNull(advice, "advice");
        validate(targetMethod);

        Class<?> targetClass = targetMethod.getDeclaringClass();
        bridge.ensureReadable(instrumentation, targetClass);
        MethodKey key = new MethodKey(targetMethod.getName(), Type.getMethodDescriptor(targetMethod));

        synchronized (lock) {
            ensureOpen();

            TransformationPlan plan = plans.computeIfAbsent(targetClass, __ -> new TransformationPlan());

            AdviceSite existing = plan.get(key);
            if (existing != null) {
                Registration registration = existing.dispatcher().add(callbacks);
                return track(new HandleImpl(targetClass, key, existing, registration));
            }

            AdviceDispatcher dispatcher = new AdviceDispatcher(targetMethod);
            Registration registration = dispatcher.add(callbacks);
            AdviceSite site = createSite(dispatcher);
            plan.putIfAbsent(key, site);

            try {
                retransform(targetClass, true);
            } catch (RuntimeException failure) {
                plan.remove(key, site);
                if (plan.isEmpty()) plans.remove(targetClass, plan);
                bridge.unregister(site.id());

                throw failure;
            }

            return track(new HandleImpl(targetClass, key, site, registration));
        }
    }

    private HandleImpl track(HandleImpl handle) {
        handles.add(handle);
        return handle;
    }

    private AdviceSite createSite(AdviceDispatcher dispatcher) {
        for (;;) {
            long id = ThreadLocalRandom.current().nextLong();

            if (bridge.register(id, dispatcher.enterHandle(), dispatcher.exitHandle(), dispatcher.exceptionHandle()))
                return new AdviceSite(id, dispatcher);
        }
    }

    private void validate(Method method) {
        int modifiers = method.getModifiers();

        if (Modifier.isAbstract(modifiers))
            throw new IllegalArgumentException("Cannot advise an abstract method: " + method);

        if (Modifier.isNative(modifiers))
            throw new IllegalArgumentException("Cannot advise a native method: " + method);

        if (!instrumentation.isModifiableClass(method.getDeclaringClass()))
            throw new IllegalArgumentException("Class is not modifiable: " + method.getDeclaringClass().getName());
    }

    private void retransform(Class<?> targetClass, boolean expectWeave) {
        transformer.prepare(targetClass);

        try {
            instrumentation.retransformClasses(targetClass);
        } catch (UnmodifiableClassException failure) {
            throw new IllegalStateException("Class became unmodifiable: " + targetClass.getName(), failure);
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException("Retransformation failed for " + targetClass.getName(), failure);
        }

        Throwable transformationFailure = transformer.failure(targetClass);
        if (transformationFailure != null)
            throw new IllegalStateException("Advice transformation failed for " + targetClass.getName(), transformationFailure);

        if (expectWeave && !transformer.wasVisited(targetClass))
            throw new IllegalStateException("Transformer was not invoked for " + targetClass.getName());
    }

    private void remove(HandleImpl handle) {
        synchronized (lock) {
            if (!handle.active) return;

            AdviceDispatcher dispatcher = handle.site.dispatcher();
            int oldIndex = dispatcher.remove(handle.registration);
            if (oldIndex < 0) {
                handle.active = false;
                handles.remove(handle);
                return;
            }

            if (!dispatcher.isEmpty()) {
                handle.active = false;
                handles.remove(handle);
                return;
            }

            TransformationPlan plan = plans.get(handle.targetClass);
            if (plan == null || !plan.remove(handle.key, handle.site)) {
                dispatcher.restore(oldIndex, handle.registration);
                throw new IllegalStateException("Advice transformation plan is inconsistent");
            }

            boolean removedClassPlan = false;
            if (plan.isEmpty()) removedClassPlan = plans.remove(handle.targetClass, plan);

            try {
                retransform(handle.targetClass, !plan.isEmpty());
            } catch (RuntimeException e) {
                if (removedClassPlan) plans.put(handle.targetClass, plan);
                plan.putIfAbsent(handle.key, handle.site);
                dispatcher.restore(oldIndex, handle.registration);

                throw e;
            }

            bridge.unregister(handle.site.id());
            handle.active = false;
            handles.remove(handle);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Advice engine is closed");
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        List<HandleImpl> snapshot;
        synchronized (lock) {
            if (closed) return;

            closed = true;
            snapshot = new ArrayList<>(handles);
        }

        RuntimeException firstFailure = null;
        for (HandleImpl handle : snapshot) {
            try {
                remove(handle);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }

        if (firstFailure != null) {
            closed = false;
            throw firstFailure;
        }

        instrumentation.removeTransformer(transformer);
    }

    private final class HandleImpl implements AdviceHandle {
        private final Class<?> targetClass;
        private final MethodKey key;
        private final AdviceSite site;
        private final Registration registration;
        private volatile boolean active = true;

        private HandleImpl(
            Class<?> targetClass,
            MethodKey key,
            AdviceSite site,
            Registration registration
        ) {
            this.targetClass = targetClass;
            this.key = key;
            this.site = site;
            this.registration = registration;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void close() {
            remove(this);
        }
    }
}
