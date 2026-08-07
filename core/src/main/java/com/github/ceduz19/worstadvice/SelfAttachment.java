package com.github.ceduz19.worstadvice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Installs Instrumentation by asking a short-lived helper JVM to attach to this process. */
final class SelfAttachment {

    private static final long ATTACH_TIMEOUT_SECONDS = 30;

    private SelfAttachment() {
    }

    static synchronized Instrumentation install() {
        Instrumentation existing = InstrumentationHolder.get();
        if (existing != null) return existing;

        Path agentJar = null;
        try {
            agentJar = createAgentJar();
            runAttacher(agentJar);

            Instrumentation attached = attachedInstrumentation();
            InstrumentationHolder.install(attached);

            return attached;
        } catch (IOException | ReflectiveOperationException failure) {
            throw new IllegalStateException("No Instrumentation was available and dynamic attachment failed; "
                    + "start with -javaagent or call WorstAdvice.create(instrumentation)", failure);
        } finally {
            if (agentJar != null) deleteTemporaryJar(agentJar);
        }
    }

    private static Path createAgentJar() throws IOException {
        Path jarPath = Files.createTempFile("worst-advice-agent-", ".jar");
        jarPath.toFile().deleteOnExit();

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Agent-Class", SelfAttachAgent.class.getName());
        attributes.putValue("Can-Retransform-Classes", "true");
        attributes.putValue("Can-Redefine-Classes", "false");

        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            addClass(output, SelfAttachAgent.class);
            addClass(output, ExternalAttacher.class);
        } catch (IOException failure) {
            deleteTemporaryJar(jarPath);
            throw failure;
        }

        return jarPath;
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        InputStream resource = type.getResourceAsStream('/' + resourceName);
        if (resource == null) throw new IOException("Cannot read class resource " + resourceName);

        output.putNextEntry(new JarEntry(resourceName));

        try (InputStream input = resource) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }

        output.closeEntry();
    }

    private static void runAttacher(Path agentJar) throws IOException {
        List<String> command = new ArrayList<>();

        command.add(javaExecutable().toString());
        if (isJavaNineOrLater()) {
            command.add("--add-modules");
            command.add("jdk.attach");
        }
        command.add("-cp");
        command.add(attacherClasspath(agentJar));
        command.add(ExternalAttacher.class.getName());
        command.add(currentProcessId());
        command.add(agentJar.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed;

        try {
            completed = process.waitFor(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dynamically attaching the WorstAdvice agent", failure);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out while dynamically attaching the WorstAdvice agent after "
                    + ATTACH_TIMEOUT_SECONDS + " seconds");
        }

        String output = readOutput(process.getInputStream());
        if (process.exitValue() != 0) {
            String detail = output.trim();
            throw new IllegalStateException("The helper JVM could not dynamically attach the WorstAdvice agent"
                    + (detail.isEmpty() ? "" : ":\n" + detail));
        }
    }

    private static Instrumentation attachedInstrumentation() throws ReflectiveOperationException {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        Class<?> agentType = Class.forName(SelfAttachAgent.class.getName(), true, systemLoader);

        Object value = agentType.getMethod("instrumentation").invoke(null);
        if (!(value instanceof Instrumentation))
            throw new IllegalStateException("The dynamically loaded agent did not provide Instrumentation");

        return (Instrumentation) value;
    }

    private static Path javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        Path path = Paths.get(System.getProperty("java.home"), "bin", executable);

        if (!Files.isRegularFile(path))
            throw new IllegalStateException("Cannot locate the Java executable at " + path);

        return path;
    }

    private static String attacherClasspath(Path agentJar) {
        String classpath = agentJar.toAbsolutePath().toString();
        if (isJavaNineOrLater()) return classpath;

        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path[] candidates = {
            javaHome.resolve("lib").resolve("tools.jar"),
            javaHome.resolve("..").resolve("lib").resolve("tools.jar").normalize()
        };

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate))
                return classpath + File.pathSeparator + candidate.toAbsolutePath();
        }

        return classpath;
    }

    private static String currentProcessId() {
        try {
            Class<?> processHandleType = Class.forName("java.lang.ProcessHandle");
            Method current = processHandleType.getMethod("current");
            Object processHandle = current.invoke(null);
            Object pid = processHandleType.getMethod("pid").invoke(processHandle);
            return String.valueOf(pid);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int separator = runtimeName.indexOf('@');
            String pid = separator < 0 ? runtimeName : runtimeName.substring(0, separator);
            try {
                Long.parseLong(pid);
            } catch (NumberFormatException failure) {
                throw new IllegalStateException("Cannot determine the current JVM process ID from " + runtimeName, failure);
            }
            return pid;
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;

        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }

        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isJavaNineOrLater() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion.startsWith("1.")) specificationVersion = specificationVersion.substring(2);

        int separator = specificationVersion.indexOf('.');
        if (separator >= 0) specificationVersion = specificationVersion.substring(0, separator);

        return Integer.parseInt(specificationVersion) >= 9;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteTemporaryJar(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The target VM may keep the agent JAR open on Windows; deleteOnExit remains registered.
        }
    }
}
