package com.github.ceduz19.worstadvice;

import com.github.ceduz19.worstadvice.api.Advice;
import com.github.ceduz19.worstadvice.api.AdviceDecision;
import com.github.ceduz19.worstadvice.api.AdviceEngine;
import com.github.ceduz19.worstadvice.api.AdviceHandle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("javaagent")
class IsolatedClassLoaderTest {
    @Test
    void advisesAClassWhoseLoaderHasNoApplicationParent() throws Exception {
        IsolatedLoader loader = new IsolatedLoader();
        Class<?> isolatedClass = loader.define(createIsolatedClass());
        Object target = isolatedClass.getConstructor().newInstance();
        Method value = isolatedClass.getMethod("value", int.class);
        Method fail = isolatedClass.getMethod("fail");

        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(Advice.class.getName()));

        try (AdviceEngine engine = WorstAdvice.create()) {
            assertNull(Class.forName(
                "com.github.ceduz19.worstadvice.bootstrap.BootstrapBridge"
            ).getClassLoader());
            AdviceHandle valueHandle = engine.advise(value, Advice.builder()
                .onEnter(context -> {
                    assertSame(target, context.target());
                    assertEquals(3, context.argument(0));
                    return AdviceDecision.returnValue(77);
                })
                .build());
            AdviceHandle failHandle = engine.advise(fail, Advice.builder()
                .onException((context, thrown) -> AdviceDecision.returnVoid())
                .build());

            assertEquals(77, value.invoke(target, 3));
            assertNull(fail.invoke(target));

            valueHandle.close();
            assertEquals(4, value.invoke(target, 3));
            failHandle.close();
        }
    }

    private static byte[] createIsolatedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            "isolated/Sample",
            null,
            "java/lang/Object",
            null
        );

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)I", null, null);
        value.visitCode();
        value.visitVarInsn(Opcodes.ILOAD, 1);
        value.visitInsn(Opcodes.ICONST_1);
        value.visitInsn(Opcodes.IADD);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();

        MethodVisitor fail = writer.visitMethod(Opcodes.ACC_PUBLIC, "fail", "()V", null, null);
        fail.visitCode();
        fail.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        fail.visitInsn(Opcodes.DUP);
        fail.visitLdcInsn("isolated failure");
        fail.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException",
            "<init>",
            "(Ljava/lang/String;)V",
            false
        );
        fail.visitInsn(Opcodes.ATHROW);
        fail.visitMaxs(0, 0);
        fail.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class IsolatedLoader extends ClassLoader {

        private IsolatedLoader() {
            super(null);
        }

        private Class<?> define(byte[] bytecode) {
            return defineClass("isolated.Sample", bytecode, 0, bytecode.length);
        }
    }
}
