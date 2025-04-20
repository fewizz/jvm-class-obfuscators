package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantInterfaceMethodref;
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

            ConstantFieldref dstCFR = dstPool.getConstant(i);
            ConstantNameAndType srcCNT = srcPool.getConstant(srcCFR.getNameAndTypeIndex());
            ConstantNameAndType dstCNT = dstPool.getConstant(dstCFR.getNameAndTypeIndex());
            dstCNT.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcCNT.getSignature(srcPool))));

            Field field = findField(owner, srcCNT.getName(srcPool), srcCNT.getSignature(srcPool));
            var translated = this.mappings.get(owner).fieldMappings.get(field);
            if (translated != null) {
                dstCNT.setNameIndex(getUTF8Index.apply(translated));
            }
        }
        for (int i = 0; i < srcJavaClass.getFields().length; ++i) {
            Field srcField = srcJavaClass.getFields()[i];
            Field dstField = dstJavaClass.getFields()[i];

            dstField.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcField.getSignature())));
            var translated = cm.fieldMappings.get(srcJavaClass.getFields()[i]);
            if (translated != null) {
                dstField.setNameIndex(getUTF8Index.apply(translated));
            }

            for (int x = 0; x < srcField.getAttributes().length; ++x) {
                this.obfuscateAttribute(srcField.getAttributes()[x], dstField.getAttributes()[x], getUTF8Index);
            }
        }

        // Methods
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var constant = srcPool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (constant instanceof ConstantMethodref srcCMR) {
                JavaClass owner = this.javaClasses.get(srcCMR.getClass(srcPool).replace('.', '/'));
                if (owner == null) { continue; }
                ConstantMethodref dstCMR = dstPool.getConstant(i);
                ConstantNameAndType srcCNT = srcPool.getConstant(srcCMR.getNameAndTypeIndex());
                ConstantNameAndType dstCNT = dstPool.getConstant(dstCMR.getNameAndTypeIndex());
                dstCNT.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcCNT.getSignature(srcPool))));
                Method srcMethod = findMethod(owner, srcCNT.getName(srcPool), srcCNT.getSignature(srcPool));
                var translated = this.mappings.get(owner).methodMappings.get(srcMethod);
                if (translated != null) {
                    dstCNT.setNameIndex(getUTF8Index.apply(translated));
                }
            }
            if (constant instanceof ConstantInterfaceMethodref srcCIMR) {
                JavaClass owner = this.javaClasses.get(srcCIMR.getClass(srcPool).replace('.', '/'));
                if (owner == null) { continue; }
                ConstantInterfaceMethodref dstCIMR = dstPool.getConstant(i);
                ConstantNameAndType srcCNT = srcPool.getConstant(srcCIMR.getNameAndTypeIndex());
                ConstantNameAndType dstCNT = dstPool.getConstant(dstCIMR.getNameAndTypeIndex());
                dstCNT.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcCNT.getSignature(srcPool))));
                Method srcMethod = findMethod(owner, srcCNT.getName(srcPool), srcCNT.getSignature(srcPool));
                var translated = this.mappings.get(owner).methodMappings.get(srcMethod);
                if (translated != null) {
                    dstCNT.setNameIndex(getUTF8Index.apply(translated));
                }
            }
        }
        for (int i = 0; i < srcJavaClass.getMethods().length; ++i) {
            Method srcMethod = srcJavaClass.getMethods()[i];
            Method dstMethod = dstJavaClass.getMethods()[i];

            dstMethod.setSignatureIndex(getUTF8Index.apply(this.patchDescriptor(srcMethod.getSignature())));
            var translated = cm.methodMappings.get(srcMethod);
            if (translated != null) {
                dstMethod.setNameIndex(getUTF8Index.apply(translated));
            }

            for (int x = 0; x < srcMethod.getAttributes().length; ++x) {
                this.obfuscateAttribute(srcMethod.getAttributes()[x], dstMethod.getAttributes()[x], getUTF8Index);
            }
        }

        // Others
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var constant = srcPool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (constant instanceof ConstantClass srcCC) {
                String ownerName = srcPool.getConstantUtf8(srcCC.getNameIndex()).getBytes().replace('.', '/');
                JavaClass owner = this.javaClasses.get(ownerName);
                if (owner == null) { continue; }
                ConstantClass dstCFR = dstPool.getConstant(i);
                String translatedName = this.mappings.get(owner).translated;
                System.out.println(ownerName+" -> "+translatedName);
                dstCFR.setNameIndex(getUTF8Index.apply(translatedName));
            }
            if (constant instanceof ConstantMethodType srcCMT) {
                ConstantMethodType dstCMT = dstPool.getConstant(i);
                String descriptor = srcPool.getConstantUtf8(srcCMT.getDescriptorIndex()).getBytes();
                dstCMT.setDescriptorIndex(getUTF8Index.apply(this.patchDescriptor(descriptor)));
            }
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

    void obfuscateAttribute(Attribute srcAttr, Attribute dstAttr, Function<String, Integer> getUTF8Index) {
        if (srcAttr instanceof LocalVariableTable srcLVT) {
            var dstLVT = (LocalVariableTable) dstAttr;
            for (int y = 0; y < srcLVT.getTableLength(); ++y) {
                LocalVariable lv = srcLVT.getLocalVariableTable()[y];
                int dstDescriptorIndex = getUTF8Index.apply(
                    this.patchDescriptor(lv.getSignature())
                );
                dstLVT.getLocalVariableTable()[y].setSignatureIndex(dstDescriptorIndex);
            }
        }
        if (srcAttr instanceof Code srcCode) {
            var dstCode = (Code) dstAttr;
            for (int i = 0; i < srcCode.getAttributes().length; ++i) {
                this.obfuscateAttribute(srcCode.getAttributes()[i], dstCode.getAttributes()[i], getUTF8Index);
            }
        }
    };

    /** Кто может ссылаться на CONSTANT_Utf8_info?
    1.  CONSTANT_Class_info (4.4.1) - имя класса
    2.  CONSTANT_String_info (4.4.3)
    3.  CONSTANT_NameAndType_info (4.4.6) - имя и дескриптор
    4.  CONSTANT_MethodType_info (4.4.9) - дескриптор
    5.  Поля (4.5) - имя и дескриптор
    6.  Методы (4.6) - имя и дескриптор
    7.  Все аттрибуты (4.7) - имя аттрибута, + :
        1. InnerClasses (4.7.6) - имя внутреннего (inner) класса
        2. Signature (4.7.9) - сигнатура (дескриптор)
        3. SourceFile (4.7.10) - название файла исходного кода
        4. LocalVariableTable (4.7.13) - имена и дескрипторы локальных переменных
        5. LocalVariableTypeTable (4.7.14)
        6. RuntimeVisibleAnnotations (4.7.16)
        7. MethodParameters (4.7.24)
    */
    /*private static Map<Integer, List<Object>> getUtf8Usages(JavaClass javaClass) {
        Map<Integer, List<Object>> result = new HashMap<>();
        for (var constant : javaClass.getConstantPool()) {
            if (constant instanceof ConstantClass cc) {
                result.computeIfAbsent(cc.getNameIndex(), k -> new ArrayList<>()).add(cc);
            }
            if (constant instanceof ConstantString cs) {
                result.computeIfAbsent(cs.getStringIndex(), k -> new ArrayList<>()).add(cs);
            }
            if (constant instanceof ConstantNameAndType cnt) {
                result.computeIfAbsent(cnt.getNameIndex(), k -> new ArrayList<>()).add(cnt);
                result.computeIfAbsent(cnt.getSignatureIndex(), k -> new ArrayList<>()).add(cnt);
            }
            if (constant instanceof ConstantMethodType cmt) {
                result.computeIfAbsent(cmt.getDescriptorIndex(), k -> new ArrayList<>()).add(cmt);
            }
        }
        for (var f : javaClass.getFields()) {
            result.computeIfAbsent(f.getNameIndex(), k -> new ArrayList<>()).add(f);
            result.computeIfAbsent(f.getSignatureIndex(), k -> new ArrayList<>()).add(f);
        }
        for (var m : javaClass.getMethods()) {
            result.computeIfAbsent(m.getNameIndex(), k -> new ArrayList<>()).add(m);
            result.computeIfAbsent(m.getSignatureIndex(), k -> new ArrayList<>()).add(m);
        }
        return result;
    }

    private static List<Object> getUtf8Usage(JavaClass javaClass, int index) {
        List<Object> result = new ArrayList<>();
        for (var constant : javaClass.getConstantPool()) {
            if (constant instanceof ConstantClass cc && cc.getNameIndex() == index) { result.add(cc); }
            if (constant instanceof ConstantString cs && cs.getStringIndex() == index) { result.add(cs); }
            if (constant instanceof ConstantNameAndType cnt &&
               (cnt.getNameIndex() == index || cnt.getSignatureIndex() == index)) { result.add(cnt); }
            if (constant instanceof ConstantMethodType cmt && cmt.getDescriptorIndex() == index) { result.add(cmt); }
        }
        for (var f : javaClass.getFields()) {
            if (f.getNameIndex() == index || f.getSignatureIndex() == index) { result.add(f); }
        }
        for (var m : javaClass.getMethods()) {
            if (m.getNameIndex() == index || m.getSignatureIndex() == index) { result.add(m); }
        }
        return result;
    }*/

    private String patchDescriptor(String desc) {
        // Можно было использовать вспомогательные методы org.apache.bcel.generic.Type
        // НО! не хочу)
        MutableInt i = new MutableInt();
        StringBuilder sb = new StringBuilder();

        Runnable next = () -> {
            while(true) {
                char ch = desc.charAt(i.intValue());
                sb.append(ch); i.increment();
                switch (ch) {
                    case '[':
                        continue;
                    case 'Z': case 'B': case 'C': case 'S': case 'I':
                    case 'F': case 'J': case 'D': case 'V':
                        return;
                    case 'L':
                        int beginning = i.getValue();
                        while (desc.charAt(i.intValue()) != ';') i.increment();
                        String type = desc.substring(beginning, i.intValue());
                        JavaClass javaClass = this.javaClasses.get(type);
                        if (javaClass != null) {
                            var m = mappings.get(javaClass);
                            sb.append(m.translated);
                        }
                        else {
                            sb.append(type);
                        }
                        sb.append(";"); i.increment();
                        return;
                    default:
                        throw new NotImplementedException("Unexpected char: "+ch);
                }
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