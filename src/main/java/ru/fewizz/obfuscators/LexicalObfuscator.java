package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.classfile.Method;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.objectweb.asm.Opcodes;

import ru.fewizz.Obfuscator;

public class LexicalObfuscator extends Obfuscator implements Opcodes {

    record ClassMapping(
        String translated,
        Map<Field, String> fieldMappings,
        Map<Method, String> methodMappings
    ) {}

    private final Map<String, JavaClass> javaClasses = new HashMap<>();
    private final Map<JavaClass, ClassMapping> mappings = new HashMap<>();

    private String generateObfuscatedName() {
        return RandomStringUtils.random(8, "abcdefghijklmnopqrstuvwxyz");
    }

    @Override
    public Supplier<byte[]> transform(byte[] classFileBytes) throws Exception {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(classFileBytes);
        ClassParser parser = new ClassParser(inputStream, "");
        JavaClass javaClass = parser.parse();

        this.javaClasses.put(javaClass.getClassName().replace('.', '/'), javaClass);

        return () -> {
            try {
                var outputStream = new ByteArrayOutputStream();
                this.obfuscate(javaClass).dump(outputStream);
                return outputStream.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public void end() throws Exception {
        for (JavaClass javaClass : this.javaClasses.values()) {
            this.createMappings(javaClass);
        }
    }

    private void createMappings(JavaClass javaClass) {
        if (this.mappings.containsKey(javaClass)) {
            return;
        }

        var baseJavaClass = this.javaClasses.values().stream().filter(
            c -> c.getClassName().equals(javaClass.getSuperclassName())
        ).findFirst().orElse(null);
        if (baseJavaClass != null) {
            createMappings(baseJavaClass);
        }

        String newName = this.generateObfuscatedName();
        ClassMapping cm = new ClassMapping(newName, new HashMap<>(), new HashMap<>());
        mappings.put(javaClass, cm);

        for (Field f : javaClass.getFields()) {
            String newFieldName = this.generateObfuscatedName();
            cm.fieldMappings.put(f, newFieldName);
        }
    }

    private JavaClass obfuscate(JavaClass srcJavaClass) throws Exception {
        ClassMapping cm = this.mappings.get(srcJavaClass);
        JavaClass dstJavaClass = srcJavaClass.copy();

        var srcPool = srcJavaClass.getConstantPool();
        var dstPool = dstJavaClass.getConstantPool();

        Function<String, Integer> getUTF8Index = (String name) -> {
            for (int i = 1; i < dstPool.getLength(); ++i) {
                var c = dstPool.getConstant(i);
                if (c instanceof ConstantLong) { ++i; continue; }
                if (c instanceof ConstantUtf8 u && u.getBytes().equals(name)) { return i; }
            }
            var newConstant = new ConstantUtf8(name);
            var newNameIndex = dstPool.getLength();
            var newPoolArray = Arrays.copyOf(dstPool.getConstantPool(), newNameIndex+1);
            newPoolArray[newNameIndex] = newConstant;
            dstPool.setConstantPool(newPoolArray);
            return newNameIndex;
        };

        // Fields
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var constant = srcPool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (!(constant instanceof ConstantFieldref srcCFR)) { continue;}

            JavaClass owner = this.javaClasses.get(srcCFR.getClass(srcPool).replace('.', '/'));
            if (owner == null) { continue; }
            ConstantNameAndType srcCNT = srcPool.getConstant(srcCFR.getNameAndTypeIndex());
            Field field = findField(owner, srcCNT.getName(srcPool), srcCNT.getSignature(srcPool));
            var fieldMapping = this.mappings.get(owner).fieldMappings.get(field);
            if (fieldMapping == null) { continue; }

            ConstantFieldref dstCFR = dstPool.getConstant(i);
            ConstantNameAndType dstCNT = dstPool.getConstant(dstCFR.getNameAndTypeIndex());
            dstCNT.setNameIndex(getUTF8Index.apply(fieldMapping));
            dstCNT.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcCNT.getSignature(srcPool))));
        }
        for (int i = 0; i < srcJavaClass.getFields().length; ++i) {
            var translated = cm.fieldMappings.get(srcJavaClass.getFields()[i]);
            if (translated == null) { continue; }
            dstJavaClass.getFields()[i].setNameIndex(getUTF8Index.apply(translated));
        }

        // Methods
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var constant = srcPool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (!(constant instanceof ConstantMethodref srcCMR)) { continue;}

            JavaClass owner = this.javaClasses.get(srcCMR.getClass(srcPool).replace('.', '/'));
            if (owner == null) { continue; }
            ConstantNameAndType srcCNT = srcPool.getConstant(srcCMR.getNameAndTypeIndex());
            Method method = findMethod(owner, srcCNT.getName(srcPool), srcCNT.getSignature(srcPool));
            var methodMapping = this.mappings.get(owner).methodMappings.get(method);
            if (methodMapping == null) { continue; }

            ConstantMethodref dstCMR = dstPool.getConstant(i);
            ConstantNameAndType dstCNT = dstPool.getConstant(dstCMR.getNameAndTypeIndex());
            dstCNT.setNameIndex(getUTF8Index.apply(methodMapping));
            dstCNT.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcCNT.getSignature(srcPool))));
        }
        for (int i = 0; i < srcJavaClass.getMethods().length; ++i) {
            Method srcMethod = srcJavaClass.getMethods()[i];
            Method dstMethod = dstJavaClass.getMethods()[i];

            for (int x = 0; x < srcMethod.getAttributes().length; ++x) {
                var a = srcMethod.getAttributes()[x];
                if (!(a instanceof LocalVariableTable lvt)) { continue; }
                for (int y = 0; y < lvt.getTableLength(); ++y) {
                    LocalVariable lv = lvt.getLocalVariableTable()[y];
                    int dstDescriptorIndex = getUTF8Index.apply(this.patchDescriptor(lv.getSignature()));
                    ((LocalVariableTable)dstMethod.getAttributes()[x]).getLocalVariableTable()[y].setSignatureIndex(dstDescriptorIndex);
                }
            }

            var translated = cm.methodMappings.get(srcMethod);
            if (translated == null) { continue; }
            dstMethod.setNameIndex(getUTF8Index.apply(translated));
        }

        // Others
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var constant = srcPool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (!(constant instanceof ConstantClass srcCC)) { continue;}

            String ownerName = srcPool.getConstantUtf8(srcCC.getNameIndex()).getBytes().replace('.', '/');
            JavaClass owner = this.javaClasses.get(ownerName);
            if (owner == null) { continue; }
            ConstantClass dstCFR = dstPool.getConstant(i);
            String translatedName = this.mappings.get(owner).translated;
            System.out.println(ownerName+" -> "+translatedName);
            dstCFR.setNameIndex(getUTF8Index.apply(translatedName));
        }

        return dstJavaClass;
    }

    private Field findField(JavaClass javaClass, String name, String descriptor) {
        for (Field f : javaClass.getFields()) {
            if (f.getName().equals(name) && f.getSignature().equals(descriptor)) {
                return f;
            }
        }
        return null;
    }

    private Method findMethod(JavaClass javaClass, String name, String descriptor) {
        for (Method m : javaClass.getMethods()) {
            if (m.getName().equals(name) && m.getSignature().equals(descriptor)) {
                return m;
            }
        }
        return null;
    }

    private String patchDescriptor(String desc) {
        // Можно было использовать вспомогательные методы org.apache.bcel.generic.Type
        // НО! не хочу)
        MutableInt i = new MutableInt();
        StringBuilder sb = new StringBuilder();

        Runnable next = () -> {
            char ch = desc.charAt(i.intValue());
            sb.append(ch); i.increment();
            switch (ch) {
                case 'Z': case 'B': case 'C': case 'S': case 'I':
                case 'F': case 'J': case 'D': case 'V': case '[':
                    break;
                case 'L':
                    int beginning = i.getValue();
                    while (desc.charAt(i.intValue()) != ';') i.increment();
                    String type = desc.substring(beginning, i.intValue());
                    JavaClass javaClass = this.javaClasses.get(type);
                    if (javaClass != null) {
                        var m = mappings.get(javaClass);
                        sb.append(m.translated);
                    }
                    else sb.append(type);
                    sb.append(";"); i.increment();
                    break;
                default:
                    throw new NotImplementedException("Unexpected char: "+ch);
            }
        };

        if (desc.startsWith("(")) {  // Метод
            sb.append("("); i.increment();
            while (desc.charAt(i.intValue()) != ')') next.run();
            sb.append(")"); i.increment();
            next.run();
        }
        else {  // Поле
            next.run();
        }

        return sb.toString();
    }

}