package ru.fewizz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public class InvokeDynamicStringConstantsObfuscator implements Opcodes {

    public static void main(String[] args) throws IOException {
        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);

        // Если на вход подается путь до файла,
        // то обрабатывается только один файл
        if (!Files.isDirectory(src)) {
            handleFile(src, dst);
            return; // Выход из программы
        }

        // Рекурсивно обрабатываются все файлы в исходной директории
        Files.walk(src).forEach(srcFile -> {
            if (Files.isDirectory(srcFile))
                return;
            Path dstFile = dst.resolve(src.relativize(srcFile));
            handleFile(srcFile, dstFile);
        });
    }

    private static void handleFile(Path src, Path dst) {
        try {
            // Создание директории назначения
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }

            // 1. Чтение байтов исходного класс-файла
            byte[] classFileBytes = Files.readAllBytes(src);

            // 2. Обработка класса
            classFileBytes = transform(classFileBytes);

            // 3. Запись байтов класс-файла в файл назначения
            Files.write(dst, classFileBytes);

        } catch (IOException | AnalyzerException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] transform(byte[] classFileBytes) throws AnalyzerException {
        var classNode = new ClassNode();
        new ClassReader(classFileBytes).accept(classNode, 0);

        for (var methodNode : classNode.methods) {
            for (AbstractInsnNode insn : methodNode.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                    var bytes = ((String) ldc.cst).getBytes(StandardCharsets.UTF_8);
                    for (int i = 0; i < bytes.length; ++i) {
                        bytes[i] ^= (byte) 0b10101010;
                    }
                    String obfuscatedString = Base64.getEncoder().encodeToString(bytes);

                    methodNode.instructions.insertBefore(ldc, new InvokeDynamicInsnNode(
                        "__deobf",
                        "()Ljava/lang/String;",
                        new Handle(
                            Opcodes.H_INVOKESTATIC,
                            classNode.name,
                            "__deobf",
                            "("+
                                "Ljava/lang/invoke/MethodHandles$Lookup;"+
                                "Ljava/lang/String;"+
                                "Ljava/lang/invoke/MethodType;"+
                                "Ljava/lang/String;"+
                            ")Ljava/lang/invoke/CallSite;",
                            false
                        ),
                        obfuscatedString
                    ));
                    methodNode.instructions.remove(ldc);
                }
            }
        }

        // Добавление статического метода `__deobf` для деобфускации строки
        /*
        private static CallSite __deobf(
            MethodHandles.Lookup lookup,
            String str,
            MethodType mt,
            String arg
        ) throws NoSuchMethodException, IllegalAccessException {
            var bytes = Base64.getDecoder().decode(arg);
            for (int i = 0; i < bytes.length; ++i) {
                bytes[i] ^= 0b10101010;
            }
            var mh = MethodHandles.constant(
                String.class,
                new String(bytes, StandardCharsets.UTF_8)
            );
            return new ConstantCallSite(mh);
        }
        */
        InsnList deobfInsns = new InsnList();
        LabelNode cycleBegin = new LabelNode();
        LabelNode cycleEnd = new LabelNode();

        deobfInsns.add(new MethodInsnNode(INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;"));
        deobfInsns.add(new VarInsnNode(ALOAD, 3));
        deobfInsns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B"));
        deobfInsns.add(new VarInsnNode(ASTORE, 4));
        deobfInsns.add(new InsnNode(ICONST_0));
        deobfInsns.add(new VarInsnNode(ISTORE, 5));

        deobfInsns.add(cycleBegin);
            deobfInsns.add(new VarInsnNode(ALOAD, 4));
            deobfInsns.add(new InsnNode(ARRAYLENGTH));
            deobfInsns.add(new VarInsnNode(ILOAD, 5));
            deobfInsns.add(new InsnNode(ISUB));
            deobfInsns.add(new JumpInsnNode(IFLE, cycleEnd));

            deobfInsns.add(new VarInsnNode(ALOAD, 4));
            deobfInsns.add(new VarInsnNode(ILOAD, 5));
            deobfInsns.add(new InsnNode(DUP2));
            deobfInsns.add(new InsnNode(BALOAD));
            deobfInsns.add(new LdcInsnNode(Integer.valueOf(0b10101010)));
            deobfInsns.add(new InsnNode(IXOR));
            deobfInsns.add(new InsnNode(BASTORE));
            deobfInsns.add(new IincInsnNode(5, 1));
            deobfInsns.add(new JumpInsnNode(GOTO, cycleBegin));
        deobfInsns.add(cycleEnd);

        deobfInsns.add(new TypeInsnNode(NEW, "java/lang/invoke/ConstantCallSite"));
        deobfInsns.add(new InsnNode(DUP));
            deobfInsns.add(new LdcInsnNode(Type.getType("Ljava/lang/String;")));
            deobfInsns.add(new TypeInsnNode(NEW, "java/lang/String"));  // создание строки
            deobfInsns.add(new InsnNode(DUP));
            deobfInsns.add(new VarInsnNode(ALOAD, 4));
            deobfInsns.add(new FieldInsnNode(
                GETSTATIC,
                "java/nio/charset/StandardCharsets",
                "UTF_8",
                "Ljava/nio/charset/Charset;"
            ));
            deobfInsns.add(new MethodInsnNode(  // инициализация строки из измененного массива байтов
                INVOKESPECIAL,
                "java/lang/String",
                "<init>",
                "([BLjava/nio/charset/Charset;)V"
            ));
            deobfInsns.add(new MethodInsnNode(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "constant",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
            ));
            deobfInsns.add(new MethodInsnNode(
                INVOKESPECIAL,
                "java/lang/invoke/ConstantCallSite",
                "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V"
            ));
        deobfInsns.add(new InsnNode(ARETURN));

        var deobfMethod = new MethodNode(
            ACC_PRIVATE | ACC_STATIC,
            "__deobf", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
            null, null
        );
        deobfMethod.instructions = deobfInsns;
        classNode.methods.add(deobfMethod);

        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

}
