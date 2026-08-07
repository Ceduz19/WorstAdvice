package com.github.ceduz19.worstadvice;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Rewrites selected methods without adding members to the target class. */
final class MethodWeaver {

    private static final String ENTER_DESCRIPTOR = "(JLjava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;";
    private static final String EXIT_DESCRIPTOR = "(JLjava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String EXCEPTION_DESCRIPTOR = "(JLjava/lang/Object;[Ljava/lang/Object;Ljava/lang/Throwable;)[Ljava/lang/Object;";

    private MethodWeaver() {
    }

    static byte[] weave(byte[] classFile, ClassLoader targetLoader, TransformationPlan plan, String bridgeOwner) {
        ClassReader reader = new ClassReader(classFile);
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        reader.accept(classNode, ClassReader.SKIP_FRAMES);

        Collection<Map.Entry<TransformationPlan.MethodKey, TransformationPlan.AdviceSite>> sites = plan.snapshot();
        for (Map.Entry<TransformationPlan.MethodKey, TransformationPlan.AdviceSite> entry : sites) {
            MethodNode method = findMethod(classNode, entry.getKey());

            if (method == null)
                throw new IllegalStateException(
                    "Method disappeared during retransformation: " + entry.getKey().name() + entry.getKey().descriptor()
                );

            weaveMethod(method, entry.getValue().id(), bridgeOwner);
        }

        ClassWriter writer = new LoaderAwareClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, targetLoader);
        classNode.accept(writer);

        return writer.toByteArray();
    }

    private static MethodNode findMethod(ClassNode classNode, TransformationPlan.MethodKey key) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(key.name()) && method.desc.equals(key.descriptor()))
                return method;
        }

        return null;
    }

    private static void weaveMethod(MethodNode method, long siteId, String bridgeOwner) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
            throw new IllegalArgumentException("Cannot advise abstract or native method " + method.name);

        if (method.instructions.size() == 0)
            throw new IllegalArgumentException("Cannot advise a method without bytecode " + method.name);

        Type methodType = Type.getMethodType(method.desc);
        Type returnType = methodType.getReturnType();
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        List<AbstractInsnNode> returns = collectReturns(method.instructions);

        int nextLocal = method.maxLocals;
        int argumentsLocal = nextLocal++;
        int signalLocal = nextLocal++;
        int resultLocal = -1;
        if (returnType.getSort() != Type.VOID) {
            resultLocal = nextLocal;
            nextLocal += returnType.getSize();
        }
        int throwableLocal = nextLocal++;
        method.maxLocals = nextLocal;

        LabelNode proceed = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode normalExit = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode recovered = new LabelNode();

        InsnList prefix = createArgumentsArray(methodType, isStatic, argumentsLocal);
        prefix.add(new LdcInsnNode(siteId));
        addReceiver(prefix, isStatic);
        prefix.add(new VarInsnNode(Opcodes.ALOAD, argumentsLocal));
        prefix.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            bridgeOwner,
            "onEnter",
            ENTER_DESCRIPTOR,
            false
        ));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, signalLocal));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, signalLocal));
        prefix.add(new JumpInsnNode(Opcodes.IFNULL, proceed));
        addReturnFromSignal(prefix, returnType, signalLocal);
        prefix.add(proceed);
        prefix.add(tryStart);
        method.instructions.insertBefore(method.instructions.getFirst(), prefix);

        for (AbstractInsnNode returnInstruction : returns) {
            InsnList replacement = new InsnList();
            if (returnType.getSort() != Type.VOID)
                replacement.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), resultLocal));

            replacement.add(new JumpInsnNode(Opcodes.GOTO, normalExit));
            method.instructions.insertBefore(returnInstruction, replacement);
            method.instructions.remove(returnInstruction);
        }

        method.instructions.add(tryEnd);
        method.instructions.add(normalExit);
        addExitCall(method.instructions, siteId, bridgeOwner, isStatic, argumentsLocal, returnType, resultLocal);
        addTypedReturn(method.instructions, returnType, resultLocal);

        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        method.instructions.add(new LdcInsnNode(siteId));
        addReceiver(method.instructions, isStatic);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, argumentsLocal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        method.instructions.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            bridgeOwner,
            "onException",
            EXCEPTION_DESCRIPTOR,
            false
        ));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, signalLocal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, signalLocal));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, recovered));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(recovered);
        addReturnFromSignal(method.instructions, returnType, signalLocal);

        method.tryCatchBlocks.add(new TryCatchBlockNode(
            tryStart,
            tryEnd,
            handler,
            "java/lang/Throwable"
        ));
    }

    private static List<AbstractInsnNode> collectReturns(InsnList instructions) {
        List<AbstractInsnNode> returns = new ArrayList<>();

        for (AbstractInsnNode current = instructions.getFirst(); current != null; current = current.getNext()) {
            int opcode = current.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) returns.add(current);
        }

        return returns;
    }

    private static InsnList createArgumentsArray(Type methodType, boolean isStatic, int argumentsLocal) {
        InsnList code = new InsnList();
        Type[] argumentTypes = methodType.getArgumentTypes();

        pushInteger(code, argumentTypes.length);
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        code.add(new VarInsnNode(Opcodes.ASTORE, argumentsLocal));

        int variable = isStatic ? 0 : 1;
        for (int index = 0; index < argumentTypes.length; index++) {
            Type argumentType = argumentTypes[index];

            code.add(new VarInsnNode(Opcodes.ALOAD, argumentsLocal));
            pushInteger(code, index);

            code.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), variable));
            addBox(code, argumentType);

            code.add(new InsnNode(Opcodes.AASTORE));

            variable += argumentType.getSize();
        }

        return code;
    }

    private static void addExitCall(
        InsnList code,
        long siteId,
        String bridgeOwner,
        boolean isStatic,
        int argumentsLocal,
        Type returnType,
        int resultLocal
    ) {
        code.add(new LdcInsnNode(siteId));
        addReceiver(code, isStatic);
        code.add(new VarInsnNode(Opcodes.ALOAD, argumentsLocal));

        if (returnType.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            code.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), resultLocal));
            addBox(code, returnType);
        }

        code.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            bridgeOwner,
            "onExit",
            EXIT_DESCRIPTOR,
            false
        ));
    }

    private static void addReceiver(InsnList code, boolean isStatic) {
        if (isStatic) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
    }

    private static void addReturnFromSignal(InsnList code, Type returnType, int signalLocal) {
        if (returnType.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.RETURN));
            return;
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, signalLocal));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.AALOAD));
        addCastOrUnbox(code, returnType);
        code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
    }

    private static void addTypedReturn(InsnList code, Type returnType, int resultLocal) {
        if (returnType.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            code.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), resultLocal));
            code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }
    }

    private static void pushInteger(InsnList code, int value) {
        if (value >= -1 && value <= 5)
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else
            code.add(new LdcInsnNode(value));
    }

    private static void addBox(InsnList code, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) return;

        String owner = wrapperOwner(type);
        code.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            owner,
            "valueOf",
            '(' + type.getDescriptor() + ")L" + owner + ';',
            false
        ));
    }

    private static void addCastOrUnbox(InsnList code, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            return;
        }

        String owner = wrapperOwner(type);
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));

        String methodName;
        switch (type.getSort()) {
            case Type.BOOLEAN:
                methodName = "booleanValue";
                break;
            case Type.CHAR:
                methodName = "charValue";
                break;
            case Type.BYTE:
                methodName = "byteValue";
                break;
            case Type.SHORT:
                methodName = "shortValue";
                break;
            case Type.INT:
                methodName = "intValue";
                break;
            case Type.FLOAT:
                methodName = "floatValue";
                break;
            case Type.LONG:
                methodName = "longValue";
                break;
            case Type.DOUBLE:
                methodName = "doubleValue";
                break;
            default:
                throw new IllegalArgumentException("Unsupported return type " + type);
        }

        code.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            owner,
            methodName,
            "()" + type.getDescriptor(),
            false
        ));
    }

    private static String wrapperOwner(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return "java/lang/Boolean";
            case Type.CHAR:
                return "java/lang/Character";
            case Type.BYTE:
                return "java/lang/Byte";
            case Type.SHORT:
                return "java/lang/Short";
            case Type.INT:
                return "java/lang/Integer";
            case Type.FLOAT:
                return "java/lang/Float";
            case Type.LONG:
                return "java/lang/Long";
            case Type.DOUBLE:
                return "java/lang/Double";
            default:
                throw new IllegalArgumentException("Not a primitive value type " + type);
        }
    }

    private static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        private LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String leftName, String rightName) {
            try {
                Class<?> left = load(leftName);
                Class<?> right = load(rightName);

                if (left.isAssignableFrom(right)) return leftName;
                if (right.isAssignableFrom(left)) return rightName;

                if (left.isInterface() || right.isInterface()) return "java/lang/Object";

                do {
                    left = left.getSuperclass();
                } while (left != null && !left.isAssignableFrom(right));

                return left == null ? "java/lang/Object" : left.getName().replace('.', '/');
            } catch (ClassNotFoundException | LinkageError ignored) {
                return "java/lang/Object";
            }
        }

        private Class<?> load(String internalName) throws ClassNotFoundException {
            String binaryName = internalName.replace('/', '.');
            return Class.forName(binaryName, false, loader);
        }
    }
}
