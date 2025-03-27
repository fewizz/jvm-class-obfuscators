package ru.fewizz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public class NaiveStringConstantsObfuscator implements Opcodes {

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
                    methodNode.instructions.insert(ldc, new MethodInsnNode(
                        INVOKESTATIC,
                        classNode.name,
                        "__deobf",
                        "(Ljava/lang/String;)Ljava/lang/String;"
                    ));
                    ldc.cst = obfuscateString((String) ldc.cst);
                }
            }
        }

        // Добавление статического метода `__deobf` для деобфускации строк
        InsnList deobfInsns = new InsnList();
        LabelNode cycleBegin = new LabelNode();
        LabelNode cycleEnd = new LabelNode();

        deobfInsns.add(new VarInsnNode(ALOAD, 0));
        deobfInsns.add(new FieldInsnNode(
            GETSTATIC,
            "java/nio/charset/StandardCharsets",
            "UTF_8",
            "Ljava/nio/charset/Charset;"
        ));
        deobfInsns.add(new MethodInsnNode(
            INVOKEVIRTUAL,
            "java/lang/String",
            "getBytes",
            "(Ljava/nio/charset/Charset;)[B"
        ));
        deobfInsns.add(new VarInsnNode(ASTORE, 1));
        deobfInsns.add(new InsnNode(ICONST_0));
        deobfInsns.add(new VarInsnNode(ISTORE, 2));

        deobfInsns.add(cycleBegin);
            deobfInsns.add(new VarInsnNode(ALOAD, 1));
            deobfInsns.add(new InsnNode(ARRAYLENGTH));
            deobfInsns.add(new VarInsnNode(ILOAD, 2));
            deobfInsns.add(new InsnNode(ISUB));
            deobfInsns.add(new JumpInsnNode(IFLE, cycleEnd));

            deobfInsns.add(new VarInsnNode(ALOAD, 1));
            deobfInsns.add(new VarInsnNode(ILOAD, 2));
            deobfInsns.add(new InsnNode(DUP2));
            deobfInsns.add(new InsnNode(BALOAD));
            deobfInsns.add(new LdcInsnNode(Integer.valueOf(0b00001010)));
            deobfInsns.add(new InsnNode(IXOR));
            deobfInsns.add(new InsnNode(BASTORE));
            deobfInsns.add(new IincInsnNode(2, 1));
            deobfInsns.add(new JumpInsnNode(GOTO, cycleBegin));
        deobfInsns.add(cycleEnd);

        deobfInsns.add(new TypeInsnNode(NEW, "java/lang/String"));  // создание строки
        deobfInsns.add(new InsnNode(DUP));
        deobfInsns.add(new VarInsnNode(ALOAD, 1));
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
        deobfInsns.add(new InsnNode(ARETURN));

        var deobfMethod = new MethodNode(
            ACC_PRIVATE | ACC_STATIC,
            "__deobf", "(Ljava/lang/String;)Ljava/lang/String;",
            null, null
        );
        deobfMethod.instructions = deobfInsns;
        classNode.methods.add(deobfMethod);

        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private static String obfuscateString(String str) {
        var charset = StandardCharsets.UTF_8;
        var bytes = str.getBytes(charset);
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] ^= (byte) 0b00001010;
        }
        return new String(bytes, charset);
    }

}
