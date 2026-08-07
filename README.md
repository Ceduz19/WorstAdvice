# WorstAdvice

WorstAdvice injects callbacks at method entry, normal exit, and exceptional exit for an already loaded method selected through `java.lang.reflect.Method`. Transformation relies exclusively on the Java Instrumentation API and ASM; it does not use Byte Buddy or equivalent frameworks.

It requires Java 8 or later and an `Instrumentation` implementation that supports retransformation.

## Build and modules

```text
api                public contracts with no dependencies
bootstrap          JDK-only bridge visible to transformed classes
core               registry, retransformation, ASM weaving, and related tests
agent              premain/agentmain entry point
distribution       fat JAR build
```

Build the fat JAR with:

```shell
./gradlew fatJar
```

The resulting artifact is `distribution/build/libs/worst-advice-1.0.0-SNAPSHOT.jar`. The only non-JDK runtime dependencies are `asm` and `asm-tree` 9.9.1. JUnit is used exclusively for tests.

## Usage

The simplest option is to call the factory directly:

```java
AdviceEngine engine = WorstAdvice.create();
```

If no `Instrumentation` instance is already available, `create()` generates a temporary agent JAR and uses a short-lived Java helper process to attach it dynamically to the current JVM. The standard startup flow does not require `-javaagent`.

Advice callbacks can then be registered:

```java
Method method = Service.class.getDeclaredMethod("load", String.class);

AdviceEngine engine = WorstAdvice.create();
AdviceHandle handle = engine.advise(method, Advice.builder()
    .onEnter(context -> {
        System.out.println("enter " + context.argument(0));
        return AdviceDecision.proceed();
    })
    .onExit((context, value) -> System.out.println("exit " + value))
    .onException((context, thrown) -> {
        if (thrown instanceof MissingDataException) {
            return AdviceDecision.returnValue("fallback");
        }
        return AdviceDecision.proceed();
    })
    .build());

// Restores the original bytecode when this is the last registration on the method.
handle.close();
engine.close();
```

The agent can still be loaded at JVM startup:

```shell
java -javaagent:worst-advice-1.0.0-SNAPSHOT.jar -jar application.jar
```

This avoids the helper process and remains useful in environments where the Attach API is disabled. A host that already owns an `Instrumentation` instance can pass it directly through `WorstAdvice.create(instrumentation)`.

For a `void` method, entry or exception advice can use:

```java
return AdviceDecision.returnVoid();
```

`returnValue(null)` remains distinct from `proceed()` and can replace the result of a method that returns a reference type.

## Semantics

- `onEnter` runs before the original method body. A return decision skips both the body and `onExit`.
- `onExit` runs only after the original body returns normally. The return value of a `void` method is represented by `null`.
- `onException` receives every `Throwable` left unhandled by the original body. `proceed()` rethrows the same object; a return decision suppresses it and returns the specified value without running `onExit`.
- An exception thrown by an advice callback propagates to the caller and is not intercepted by the other hooks in the same group.
- Multiple registrations on the same method run entry advice in registration order and exit/exception advice in reverse order.
- The arguments in `AdviceContext` are those observed at entry and are exposed through defensive copies. The receiver is `null` for static methods.

Replacement values that are incompatible with the method return type produce the normal `ClassCastException` or unboxing error at the method boundary.

## Isolated class loaders, JPMS, and relocation

The injected bytecode does not mention any API type: signatures contain only primitives, `Object`, arrays, and `Throwable`. Before the first transformation, the core creates a small temporary JAR containing only the bridge and appends it to the bootstrap class loader search. Callbacks remain in the library class loader and are reached from the bridge through JDK `MethodHandle` instances.

The binary name of the bridge is not hard-coded. It is obtained from the bytecode descriptor in `BridgeTypeMarker`, which relocation rewrites together with the other references. This makes it safe to relocate the library packages and, if desired, ASM.

### Relocation and startup modes

The original fat JAR produced by WorstAdvice already contains the required manifest and can be passed directly to `-javaagent`.

When WorstAdvice is embedded and relocated inside another application's final fat JAR, the dependency manifest does not normally become the manifest of the new artifact. If that final JAR must be used with `-javaagent`, its build must declare the entry point under its relocated name and enable retransformation.

For example, with:

```groovy
relocate 'com.github.ceduz19.worstadvice', 'my.package.lib'
```

the final JAR manifest must contain:

```groovy
manifest {
    attributes(
        'Premain-Class': 'my.package.lib.agent.WorstAdviceAgent',
        'Agent-Class': 'my.package.lib.agent.WorstAdviceAgent',
        'Can-Retransform-Classes': 'true',
        'Can-Redefine-Classes': 'false'
    )
}
```

`Premain-Class` is required for startup through `-javaagent`; `Agent-Class` is required when the final JAR is loaded by an external process through the Attach API. `Can-Retransform-Classes: true` is required in both cases.

The automatic self-attach performed by:

```java
WorstAdvice.create();
```

does not require any WorstAdvice attributes in the application manifest. The library generates a temporary agent JAR with its own manifest and the effective, possibly relocated, name of `SelfAttachAgent`. `WorstAdvice.create(instrumentation)` is also independent of the manifest.

This should not be confused with externally attaching the final JAR: in that case, `Agent-Class` must be present.

For classes in named modules, Instrumentation adds a read edge to the unnamed module containing the bridge. An end-to-end test defines and instruments a class loaded by a `ClassLoader` whose parent is `null`.

## Intentional limitations

- The API accepts `Method`, not `Constructor`; constructors and initializers are not supported.
- `abstract` and `native` methods do not have a transformable body and are rejected.
- Self-attach requires a JDK runtime with the Attach API enabled and permission to start a short-lived Java process as the same user. If the environment disables dynamic attachment, use `-javaagent` or provide `Instrumentation` explicitly.
- Removing the last handle requires another retransformation of the class.

