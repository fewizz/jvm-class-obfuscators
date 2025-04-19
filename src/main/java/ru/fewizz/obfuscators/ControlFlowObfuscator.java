package ru.fewizz.obfuscators;

import static org.objectweb.asm.tree.analysis.BasicValue.DOUBLE_VALUE;
import static org.objectweb.asm.tree.analysis.BasicValue.FLOAT_VALUE;
import static org.objectweb.asm.tree.analysis.BasicValue.INT_VALUE;
import static org.objectweb.asm.tree.analysis.BasicValue.LONG_VALUE;
import static org.objectweb.asm.tree.analysis.BasicValue.UNINITIALIZED_VALUE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import ru.fewizz.Obfuscator;

public class ControlFlowObfuscator extends Obfuscator implements Opcodes {

    @Override
    @SuppressWarnings("unused")
    public Supplier<byte[]> transform(byte[] classFileBytes) throws AnalyzerException {
        // Создание представления класса в виде объекта
        var classNode = new ClassNode();
        new ClassReader(classFileBytes).accept(classNode, 0);

        Interpreter<BasicValue> interpreter = new SimpleVerifier();

        // Псевдослучайный генератор случайных чисел,
        // для определения позиции свободной для обработки функции
        Random random = new Random(0);

        // Прохождение по всем методам класса
        for (MethodNode methodNode : classNode.methods) {
            // Пропускаем конструкторы, либо методы,
            // не имеющие инструкций (нативные, абстрактные и т.д.)
            if (methodNode.name.equals("<init>") || methodNode.instructions.getFirst() == null) {
                continue;
            }

            // Максимальный размер стека метода выставляется на максимальное значение,
            // позже будет перерасчитан
            methodNode.maxStack = 65535;

            // Анализируется использование стека и локальных переменных
            var analyzer = new Analyzer<>(interpreter);
            var frames = new ArrayList<>(
                Arrays.asList(analyzer.analyze(classNode.name, methodNode))
            );
            if (false) {
                // Получение множества разрешенных для обработки инструкций
                Set<AbstractInsnNode> available = collectAllowedInsns(methodNode, frames);

                // Функция для случайного "вынимания" из множества
                // одной свободной инструкции
                Supplier<AbstractInsnNode> popRandomAvailableInsn = () -> {
                    var allowedList = new ArrayList<>(available);
                    int index = random.nextInt(allowedList.size());
                    AbstractInsnNode insn = allowedList.get(index);
                    available.remove(insn);
                    return insn;
                };

                // Выбираются две случайные инструкции, и между ними устанавливается
                // ложная связь
                while (available.size() >= 2) {
                    var src = popRandomAvailableInsn.get();
                    var dst = popRandomAvailableInsn.get();
                    insertFakeBranch(
                        classNode, methodNode, frames,
                        interpreter, src, dst
                    );
                }
            } else {
                // Функция для случайного выбора
                // одной разрешенной для обработки инструкции
                Supplier<AbstractInsnNode> popRandomAllowedInsn = () -> {
                    // Получение множества разрешенных для обработки инструкций
                    Set<AbstractInsnNode> available = collectAllowedInsns(methodNode, frames);
                    var availableList = new ArrayList<>(available);
                    int index = random.nextInt(availableList.size());
                    AbstractInsnNode insn = availableList.get(index);
                    return insn;
                };
                int count = methodNode.instructions.size() / 4;
                for (int i = 0; i < count; ++i) {
                    var src = popRandomAllowedInsn.get();
                    var dst = popRandomAllowedInsn.get();
                    insertFakeBranch(
                        classNode, methodNode, frames,
                        interpreter, src, dst
                    );
                }
            }
        }

        // Обратное преобразование объекта класс-файла в байты,
        // С пересчетом максимального размера стека и фреймов
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(classWriter);
        return () -> classWriter.toByteArray();
    }

    private static Set<AbstractInsnNode> collectAllowedInsns(
        MethodNode methodNode,
        List<Frame<BasicValue>> frames
    ) {
        Set<AbstractInsnNode> allowed = new HashSet<>();

        // Прохождение по всем инструкциям метода
        for (
            AbstractInsnNode insn = methodNode.instructions.getFirst();
            insn.getNext() != null;
            insn = insn.getNext()
        ) {
            if (frames.get(methodNode.instructions.indexOf(insn)) == null) {
                continue;
            }
            // Пропускаются псевдоинструкции
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }

            // Пропускается последовательность инструкций,
            // Отвечающая за создание и инициализацию объекта
            if (insn.getOpcode() == NEW) {
                insn = insn.getNext();
                if (insn == null || insn.getOpcode() != DUP) {
                    throw new RuntimeException();
                }
                insn = insn.getNext();

                while (insn.getOpcode() != INVOKESPECIAL) {
                    insn = insn.getNext();
                }
                insn = insn.getNext();
            }

            // Доабвление свободной инструкции в множество
            allowed.add(insn);
        }

        return allowed;
    }

    static void insertFakeBranch(
        ClassNode classNode, MethodNode methodNode,
        List<Frame<BasicValue>> frames,
        Interpreter<BasicValue> interpreter,
        AbstractInsnNode srcInsn,
        AbstractInsnNode dstInsn
    ) throws AnalyzerException {
        var insns = methodNode.instructions;
        Frame<BasicValue> srcFrame = frames.get(insns.indexOf(srcInsn));
        Frame<BasicValue> dstFrame = frames.get(insns.indexOf(dstInsn));

        Label fakeInsnsEndLabel = new Label();
        var fakeInsns = new InsnList(); // Список новых инструкций
        var fakeFrames = new ArrayList<Frame<BasicValue>>();

        final Frame<BasicValue> frame = new Frame<>(srcFrame);

        Consumer<AbstractInsnNode> addInsn = (AbstractInsnNode insn) -> {
            fakeInsns.add(insn);
            fakeFrames.add(new Frame<>(frame));
            if (insn instanceof LabelNode) {
                // Псевдо инструкция, пропускаем
            }
            else {
                try {
                    frame.execute(insn, interpreter);
                } catch (AnalyzerException e) {
                    e.printStackTrace();
                }
            }
        };

        // на стек загружается 0
        addInsn.accept(new InsnNode(ICONST_0));

        // Если значение на стеке равно 0 (всегда истинно),
        // прыгнуть после списка добавляемых нами инструкций
        addInsn.accept(new JumpInsnNode(IFEQ, new LabelNode(fakeInsnsEndLabel)));

        if (frame.getStackSize() != srcFrame.getStackSize()) {
            throw new RuntimeException();
        }

        // Добавление ложного прыжка на инструкцию dst,
        // перед эти необходимо привести стек к нужному размеру и наполнению
        fixupStackAndLocalsUsage(srcFrame, dstFrame, addInsn);

        LabelNode dstLabelInsn;

        if (dstInsn instanceof LabelNode labelNode0) {
            dstLabelInsn = labelNode0;
        }
        else {
            dstLabelInsn = new LabelNode();
            frames.add(insns.indexOf(dstInsn), new Frame<>(dstFrame));
            insns.insertBefore(dstInsn, dstLabelInsn);
        }

        // Сам безусловный прыжок
        addInsn.accept(new JumpInsnNode(GOTO, dstLabelInsn));

        frame.init(srcFrame);
        addInsn.accept(new LabelNode(fakeInsnsEndLabel));

        if (fakeInsns.size() != fakeFrames.size())
            throw new RuntimeException();

        frames.addAll(insns.indexOf(srcInsn), fakeFrames);

        // Вставка созданного списка инструкций в исходный список
        insns.insertBefore(srcInsn, fakeInsns);

        if (insns.size() != frames.size())
            throw new RuntimeException();
    }

    private static void fixupStackAndLocalsUsage(
        Frame<BasicValue> srcFrame,
        Frame<BasicValue> dstFrame,
        Consumer<AbstractInsnNode> addInsn
    ) {
        for (int i = 0; i < dstFrame.getLocals(); ++i) {
            var value = dstFrame.getLocal(i);

            if (value.equals(srcFrame.getLocal(i))) {
                continue;
            }

            if (value == UNINITIALIZED_VALUE) {
                continue;
            }

            if (value == DOUBLE_VALUE) {
                addInsn.accept(new InsnNode(DCONST_0));
                addInsn.accept(new VarInsnNode(FSTORE, i));
            }
            else if (value == FLOAT_VALUE) {
                addInsn.accept(new InsnNode(FCONST_0));
                addInsn.accept(new VarInsnNode(FSTORE, i));
            }
            else if (value == LONG_VALUE) {
                addInsn.accept(new InsnNode(LCONST_0));
                addInsn.accept(new VarInsnNode(LSTORE, i));
            }
            else if (value == INT_VALUE) {
                addInsn.accept(new InsnNode(ICONST_0));
                addInsn.accept(new VarInsnNode(ISTORE, i));
            }
            else {
                addInsn.accept(new InsnNode(ACONST_NULL));
                addInsn.accept(new TypeInsnNode(CHECKCAST, value.getType().getInternalName()));
                addInsn.accept(new VarInsnNode(ASTORE, i));
            }
        }

        int diffIndex = 0;
        for (; diffIndex < srcFrame.getStackSize() && diffIndex < dstFrame.getStackSize(); ++diffIndex) {
            if (!srcFrame.getStack(diffIndex).equals(dstFrame.getStack(diffIndex))) {
                break;
            }
        }

        for (int i = srcFrame.getStackSize() - 1; i >= diffIndex ; --i) {
            var value = srcFrame.getStack(i);
            if (value == DOUBLE_VALUE || value == LONG_VALUE) {
                addInsn.accept(new InsnNode(POP2));
            }
            else {
                addInsn.accept(new InsnNode(POP));
            }
        }

        for (int i = diffIndex; i < dstFrame.getStackSize(); ++i) {
            var value = dstFrame.getStack(i);
            if (value == DOUBLE_VALUE) addInsn.accept(new InsnNode(DCONST_0));
            else if (value == FLOAT_VALUE) addInsn.accept(new InsnNode(FCONST_0));
            else if (value == LONG_VALUE) addInsn.accept(new InsnNode(LCONST_0));
            else if (value == INT_VALUE) addInsn.accept(new InsnNode(ICONST_0));
            else {
                addInsn.accept(new InsnNode(ACONST_NULL));
                addInsn.accept(new TypeInsnNode(CHECKCAST, value.getType().getInternalName()));
            }
        }
    }

}
