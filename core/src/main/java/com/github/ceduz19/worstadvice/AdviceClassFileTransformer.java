package com.github.ceduz19.worstadvice;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Applies current method plans during explicit class retransformation. */
final class AdviceClassFileTransformer implements ClassFileTransformer {

    private final ConcurrentMap<Class<?>, TransformationPlan> plans;
    private final String bridgeOwner;
    private final ConcurrentMap<Class<?>, Throwable> failures = new ConcurrentHashMap<>();
    private final Set<Class<?>> visits = Collections.newSetFromMap(new ConcurrentHashMap<>());

    AdviceClassFileTransformer(ConcurrentMap<Class<?>, TransformationPlan> plans, String bridgeOwner) {
        this.plans = plans;
        this.bridgeOwner = bridgeOwner;
    }

    void prepare(Class<?> target) {
        failures.remove(target);
        visits.remove(target);
    }

    boolean wasVisited(Class<?> target) {
        return visits.remove(target);
    }

    Throwable failure(Class<?> target) {
        return failures.remove(target);
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classFileBuffer
    ) throws IllegalClassFormatException {
        if (classBeingRedefined == null) return null;

        TransformationPlan plan = plans.get(classBeingRedefined);
        if (plan == null || plan.isEmpty()) return null;

        visits.add(classBeingRedefined);
        try {
            return MethodWeaver.weave(classFileBuffer, loader, plan, bridgeOwner);
        } catch (Throwable failure) {
            failures.put(classBeingRedefined, failure);

            IllegalClassFormatException wrapped = new IllegalClassFormatException("Cannot weave advice into " + classBeingRedefined.getName());
            wrapped.initCause(failure);
            throw wrapped;
        }
    }
}
