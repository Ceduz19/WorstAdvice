package com.github.ceduz19.worstadvice;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Installs and talks to the bootstrap-loaded bridge. */
final class BootstrapBridgeAccess {

    private static final Object INSTALL_LOCK = new Object();
    private static volatile BootstrapBridgeAccess installed;

    private final Class<?> bridgeClass;
    private final Method register;
    private final Method unregister;
    @SuppressWarnings("unused")
    private final JarFile retainedBootstrapJar;

    private BootstrapBridgeAccess(Class<?> bridgeClass, JarFile retainedBootstrapJar) {
        this.bridgeClass = bridgeClass;
        this.retainedBootstrapJar = retainedBootstrapJar;
        try {
            register = bridgeClass.getMethod(
                "register",
                long.class,
                MethodHandle.class,
                MethodHandle.class,
                MethodHandle.class
            );
            unregister = bridgeClass.getMethod("unregister", long.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Incompatible bootstrap bridge already installed", exception);
        }
    }

    static BootstrapBridgeAccess install(Instrumentation instrumentation) {
        BootstrapBridgeAccess current = installed;
        if (current != null) return current;

        synchronized (INSTALL_LOCK) {
            current = installed;

            if (current == null) {
                current = installOnce(instrumentation);
                installed = current;
            }

            return current;
        }
    }

    private static BootstrapBridgeAccess installOnce(Instrumentation instrumentation) {
        String binaryName = locateBridgeBinaryName();
        Class<?> existing = findBootstrapClass(binaryName);
        if (existing != null) return new BootstrapBridgeAccess(existing, null);

        Path jarPath;
        try {
            jarPath = writeBootstrapJar(binaryName);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create the bootstrap bridge JAR", exception);
        }

        try {
            JarFile jarFile = new JarFile(jarPath.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
            Class<?> loaded = Class.forName(binaryName, true, null);
            return new BootstrapBridgeAccess(loaded, jarFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot open the bootstrap bridge JAR", exception);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Bootstrap bridge was not made visible", exception);
        }
    }

    private static Class<?> findBootstrapClass(String binaryName) {
        try {
            return Class.forName(binaryName, true, null);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String locateBridgeBinaryName() {
        InputStream input = BootstrapBridgeAccess.class.getResourceAsStream("BridgeTypeMarker.class");
        if (input == null) throw new IllegalStateException("Cannot locate the bridge type marker");

        try (InputStream source = input) {
            ClassNode marker = new ClassNode(Opcodes.ASM9);
            new ClassReader(source).accept(
                marker,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );

            for (FieldNode field : marker.fields) {
                if (field.name.equals("bridge"))
                    return Type.getType(field.desc).getClassName();
            }

            throw new IllegalStateException("Bridge marker field is missing");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the bridge type marker", failure);
        }
    }

    private static Path writeBootstrapJar(String binaryName) throws IOException {
        String resourceName = binaryName.replace('.', '/') + ".class";
        InputStream input = BootstrapBridgeAccess.class.getResourceAsStream('/' + resourceName);
        if (input == null) throw new IOException("Cannot locate " + resourceName);

        Path path = Files.createTempFile("worst-advice-bootstrap-", ".jar");
        path.toFile().deleteOnExit();
        
        try (InputStream source = input;
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))
        ) {
            output.putNextEntry(new JarEntry(resourceName));
            
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            
            output.closeEntry();
        }
        
        return path;
    }

    String internalName() {
        return bridgeClass.getName().replace('.', '/');
    }

    boolean register(
        long id,
        MethodHandle enter,
        MethodHandle exit,
        MethodHandle exception
    ) {
        try {
            return (Boolean) register.invoke(null, id, enter, exit, exception);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access the bootstrap bridge", failure);
        } catch (InvocationTargetException failure) {
            throw propagateBridgeFailure(failure);
        }
    }

    void unregister(long id) {
        try {
            unregister.invoke(null, id);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access the bootstrap bridge", failure);
        } catch (InvocationTargetException failure) {
            throw propagateBridgeFailure(failure);
        }
    }

    private static IllegalStateException propagateBridgeFailure(InvocationTargetException failure) {
        Throwable cause = failure.getCause();

        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;

        return new IllegalStateException("Bootstrap bridge invocation failed", cause);
    }

    /** Adds a JPMS read edge when the advised class belongs to a named module. */
    @SuppressWarnings("JavaReflectionMemberAccess")
    void ensureReadable(Instrumentation instrumentation, Class<?> targetClass) {
        try {
            Class<?> moduleType = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object targetModule = getModule.invoke(targetClass);
            Object bridgeModule = getModule.invoke(bridgeClass);
            Method isNamed = moduleType.getMethod("isNamed");
            if (!(Boolean) isNamed.invoke(targetModule)) return;

            Method redefineModule = Instrumentation.class.getMethod(
                "redefineModule",
                moduleType,
                Set.class,
                Map.class,
                Map.class,
                Set.class,
                Map.class
            );
            redefineModule.invoke(
                instrumentation,
                targetModule,
                Collections.singleton(bridgeModule),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap()
            );
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Java 8 has no module system.
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot add the bootstrap bridge module read edge", failure);
        } catch (InvocationTargetException failure) {
            throw propagateBridgeFailure(failure);
        }
    }
}
