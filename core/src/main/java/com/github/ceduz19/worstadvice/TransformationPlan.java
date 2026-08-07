package com.github.ceduz19.worstadvice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Mutable per-class plan read concurrently by the transformer. */
final class TransformationPlan {

    private final ConcurrentMap<MethodKey, AdviceSite> methods = new ConcurrentHashMap<>();

    AdviceSite get(MethodKey key) {
        return methods.get(key);
    }

    AdviceSite putIfAbsent(MethodKey key, AdviceSite site) {
        return methods.putIfAbsent(key, site);
    }

    boolean remove(MethodKey key, AdviceSite site) {
        return methods.remove(key, site);
    }

    boolean isEmpty() {
        return methods.isEmpty();
    }

    Collection<Map.Entry<MethodKey, AdviceSite>> snapshot() {
        return new ArrayList<>(methods.entrySet());
    }

    static final class MethodKey {

        private final String name;
        private final String descriptor;

        MethodKey(String name, String descriptor) {
            this.name = Objects.requireNonNull(name, "name");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        String name() {
            return name;
        }

        String descriptor() {
            return descriptor;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) return true;
            if (!(candidate instanceof MethodKey)) return false;

            MethodKey other = (MethodKey) candidate;
            return name.equals(other.name) && descriptor.equals(other.descriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, descriptor);
        }
    }

    static final class AdviceSite {

        private final long id;
        private final AdviceDispatcher dispatcher;

        AdviceSite(long id, AdviceDispatcher dispatcher) {
            this.id = id;
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        }

        long id() {
            return id;
        }

        AdviceDispatcher dispatcher() {
            return dispatcher;
        }
    }
}
