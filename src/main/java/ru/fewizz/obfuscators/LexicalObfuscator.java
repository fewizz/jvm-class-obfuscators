package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.Opcodes;

import ru.fewizz.Obfuscator;

public class LexicalObfuscator extends Obfuscator implements Opcodes {

    record ClassMapping(
        String translated,
        Map<String, String> fieldMappings,
        Map<String, String> methodMappings
    ) {};

    private final List<JavaClass> javaClasses = new ArrayList<>();
    private final Map<JavaClass, CompletableFuture<Pair<byte[], String>>> futures = new HashMap<>();
    private final Map<String, ClassMapping> mappings = new HashMap<>();

    @Override
    public CompletableFuture<Pair<byte[], String>> transform(byte[] classFileBytes) throws Exception {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(classFileBytes);
        ClassParser parser = new ClassParser(inputStream, "");
        JavaClass javaClass = parser.parse();

        CompletableFuture<Pair<byte[], String>> future = new CompletableFuture<>();
        this.javaClasses.add(javaClass);
        this.futures.put(javaClass, future);
        return future;
    }

    @Override
    public void end() throws Exception {
        Set<String> usedClassNames = new HashSet<>();

        for (JavaClass javaClass : this.javaClasses) {
            String newName;
            while (!usedClassNames.add(newName = RandomStringUtils.random(8, "abcdefghijklmnopqrstuvwxyz")));
            ClassMapping cm = new ClassMapping(newName, new HashMap<>(), new HashMap<>());
            System.out.println(javaClass.getClassName() + " -> " + newName);
            mappings.put(javaClass.getClassName().replace('.', '/'), cm);
        }

        for (JavaClass javaClass : this.javaClasses) {
            var cp = javaClass.getConstantPool();
            for (int i = 1; i < cp.getLength(); ++i) {
                var c = cp.getConstant(i);
                if (c instanceof ConstantLong) {
                    ++i;
                }
                if (c instanceof ConstantClass) {
                    var cc = (ConstantClass) c;
                    String ownerName = cp.getConstantUtf8(cc.getNameIndex()).getBytes();
                    var mapping = mappings.get(ownerName);

                    if (mapping != null) {
                        cp.setConstant(cc.getNameIndex(), new ConstantUtf8(mapping.translated));
                    }
                }
                if (c instanceof ConstantNameAndType) {
                    var cnt = (ConstantNameAndType) c;
                    String descriptior = cp.getConstantUtf8(cnt.getSignatureIndex()).getBytes();
                    String patchedDescriptor = this.patchDescriptor(descriptior);
                    cp.setConstant(cnt.getSignatureIndex(), new ConstantUtf8(patchedDescriptor));
                }
            }
            for (Method m : javaClass.getMethods()) {
                String descriptior = cp.getConstantUtf8(m.getSignatureIndex()).getBytes();
                String patchedDescriptor = this.patchDescriptor(descriptior);
                cp.setConstant(m.getSignatureIndex(), new ConstantUtf8(patchedDescriptor));
            }
        }

        for (JavaClass javaClass : this.javaClasses) {
            var outputStream = new ByteArrayOutputStream();
            javaClass.dump(outputStream);
            byte[] bytes = outputStream.toByteArray();
            ConstantClass thisClass = (ConstantClass) javaClass.getConstantPool().getConstant(javaClass.getClassNameIndex());
            this.futures.get(javaClass).complete(Pair.of(
                bytes,
                javaClass.getConstantPool().getConstantUtf8(thisClass.getNameIndex()).getBytes()
            ));
        }
    }

    private String patchDescriptor(String desc) {
        // Можно было использовать вспомогательные методы org.apache.bcel.generic.Type
        // НО! не хочу)
        MutableInt i = new MutableInt();
        StringBuilder sb = new StringBuilder();

        Runnable next = () -> {
            char ch = desc.charAt(i.intValue());
            switch (ch) {
                case 'Z': case 'B': case 'C': case 'S': case 'I':
                case 'F': case 'J': case 'D': case 'V': case '[':
                    sb.append(ch);
                    break;
                case 'L':
                    sb.append("L");
                    i.increment();
                    int beginning = i.getValue();
                    while (desc.charAt(i.intValue()) != ';') i.increment();
                    String type = desc.substring(beginning, i.intValue());
                    var m = mappings.get(type);
                    if (m != null) sb.append(m.translated);
                    else sb.append(type);
                    sb.append(";");
                    break;
                default:
                    throw new NotImplementedException("Unexpected char: "+ch);
            }
            i.increment();
        };

        if (desc.startsWith("(")) {
            sb.append("("); i.increment();
            while (desc.charAt(i.intValue()) != ')') next.run();
            sb.append(")"); i.increment();
            next.run();
        }
        else {
            next.run();
        }

        return sb.toString();
    }

}