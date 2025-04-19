package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
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
    private final Map<JavaClass, byte[]> results = new HashMap<>();
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

        return () -> this.results.get(javaClass);
    }

    public void end() throws Exception {
        // Задать маппинги
        for (JavaClass javaClass : this.javaClasses.values()) {
            this.createMappings(javaClass);
        }

        // Применить маппинги
        for (JavaClass javaClass : this.javaClasses.values()) {
            this.obfuscate(javaClass);
        }

        // Конвертировать обратно в байт-код
        for (JavaClass javaClass : this.javaClasses.values()) {
            var outputStream = new ByteArrayOutputStream();
            javaClass.dump(outputStream);
            byte[] bytes = outputStream.toByteArray();
            this.results.put(javaClass, bytes);
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

    private void obfuscate(JavaClass javaClass) throws Exception {
        var pool = javaClass.getConstantPool();

        ClassMapping thisCm = this.mappings.get(javaClass);

        for (int i = 1; i < pool.getLength(); ++i) {
            var constant = pool.getConstant(i);
            if (constant instanceof ConstantLong) { ++i; continue; }
            if (!(constant instanceof ConstantFieldref cfr)) { continue;}

            String ownerName = cfr.getClass(pool).replace('.', '/');
            JavaClass owner = this.javaClasses.get(ownerName);
            if (owner == null) { continue; }

            ClassMapping cm = this.mappings.get(owner);
            ConstantNameAndType cnt = pool.getConstant(cfr.getNameAndTypeIndex());

            Field field = findField(owner, cnt.getName(pool), cnt.getSignature(pool));

            var fieldMapping = cm.fieldMappings.get(field);
            if (fieldMapping == null) { continue; }

            var newName = new ConstantUtf8(fieldMapping);
            var newNameIndex = pool.getLength();

            var newPoolArray = Arrays.copyOf(pool.getConstantPool(), newNameIndex+1);
            newPoolArray[newNameIndex] = newName;
            pool.setConstantPool(newPoolArray);

            cnt.setNameIndex(newNameIndex);
        }

        for (Field field : javaClass.getFields()) {
            var fieldMapping = thisCm.fieldMappings.get(field);
            if (fieldMapping == null) { continue; }

            var newName = new ConstantUtf8(fieldMapping);
            var newNameIndex = pool.getLength();

            var newPoolArray = Arrays.copyOf(pool.getConstantPool(), newNameIndex+1);
            newPoolArray[newNameIndex] = newName;
            pool.setConstantPool(newPoolArray);

            field.setNameIndex(newNameIndex);
        }
    }

    private Field findField(JavaClass javaClass, String name, String descriptor) {
        for (Field f : javaClass.getFields()) {
            if (f.getName().equals(name) && f.getSignature().equals(descriptor)) {
                return f;
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