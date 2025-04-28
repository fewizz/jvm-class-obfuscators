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
import java.util.stream.Stream;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantDouble;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantInterfaceMethodref;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantModule;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPackage;
import org.apache.bcel.classfile.ConstantPool;
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
import org.apache.commons.lang3.tuple.Pair;
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

        var baseJavaClass = this.javaClasses.get(javaClass.getSuperclassName().replace(".", "/"));
        if (baseJavaClass != null) {
            createMappings(baseJavaClass);
        }

        for (var interfaceName : javaClass.getInterfaceNames()) {
            var intface = this.javaClasses.get(interfaceName.replace(".", "/"));
            if (intface != null) {
                createMappings(intface);
            }
        }

        String newName = this.generateObfuscatedName();
        System.out.println(javaClass.getClassName()+" -> "+newName);
        ClassMapping cm = new ClassMapping(newName, new HashMap<>(), new HashMap<>());
        this.mappings.put(javaClass, cm);

        for (Field f : javaClass.getFields()) {
            String newFieldName = this.generateObfuscatedName();
            cm.fieldMappings.put(f, newFieldName);
        }

        for (Method m : javaClass.getMethods()) {
            if (m.getName().equals("<init>") || m.getName().equals("<clinit>")) {
                continue;
            }
            if (m.getName().equals("main") && m.getSignature().equals("([Ljava/lang/String;)V")) {
                continue;
            }
            var e = findMethodResolved(javaClass, m.getName(), m.getSignature(), true);
            if (e.getValue() != null) {
                var superMethod = e.getValue();
                var superClass = e.getKey();
                var superMappings = this.mappings.get(superClass);
                if (superMappings != null) {
                    String methodName = superMappings.methodMappings.get(superMethod);
                    if (methodName != null) {
                        cm.methodMappings.put(m, methodName);
                        System.out.println("\t"+m.toString()+" -> "+methodName);
                    }
                }
            }
            else {
                String newMethodName = this.generateObfuscatedName();
                System.out.println("\t"+m.toString()+" -> "+newMethodName);
                cm.methodMappings.put(m, newMethodName);
            }
        }
    }

    private JavaClass obfuscate(JavaClass srcJavaClass) throws Exception {
        ClassMapping cm = this.mappings.get(srcJavaClass);
        JavaClass dstJavaClass = srcJavaClass.copy();

        var srcPool = srcJavaClass.getConstantPool();
        var dstPool = dstJavaClass.getConstantPool();

        // Fields
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var srcC = srcPool.getConstant(i);
            if (srcC instanceof ConstantLong || srcC instanceof ConstantDouble) { ++i; continue; }
            if (!(srcC instanceof ConstantFieldref srcCFR)) { continue;}

            JavaClass owner = this.javaClasses.get(srcCFR.getClass(srcPool).replace('.', '/'));
            if (owner == null) { continue; }

            ConstantFieldref dstCFR = dstPool.getConstant(i);
            ConstantNameAndType srcCNT = srcPool.getConstant(srcCFR.getNameAndTypeIndex());
            ConstantNameAndType dstCNT = dstPool.getConstant(dstCFR.getNameAndTypeIndex());
            dstCNT = new ConstantNameAndType(dstCNT.getNameIndex(), dstCNT.getSignatureIndex());

            String srcDesc = srcCNT.getSignature(srcPool);
            String dstDesc = this.patchDescriptor(srcDesc);
            dstCNT.setSignatureIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));

            String srcName = srcCNT.getName(srcPool);
            Field field = findField(owner, srcName, srcCNT.getSignature(srcPool));
            String dstName = this.mappings.get(owner).fieldMappings.get(field);
            if (dstName != null) {
                dstCNT.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));
            }
            dstCFR.setNameAndTypeIndex(addConstant(dstPool, dstCNT));
        }
        for (int i = 0; i < srcJavaClass.getFields().length; ++i) {
            Field srcField = srcJavaClass.getFields()[i];
            Field dstField = dstJavaClass.getFields()[i];

            String srcDesc = srcField.getSignature();
            String dstDesc = this.patchDescriptor(srcDesc);
            dstField.setSignatureIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));

            String srcName = srcField.getName();
            String dstName = cm.fieldMappings.get(srcField);
            if (dstName != null) {
                dstField.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));
            }

            for (int x = 0; x < srcField.getAttributes().length; ++x) {
                this.obfuscateAttribute(
                    dstJavaClass, srcField.getAttributes()[x], dstField.getAttributes()[x], o -> true
                );
            }
        }

        // Methods
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var srcC = srcPool.getConstant(i);
            if (srcC instanceof ConstantLong || srcC instanceof ConstantDouble) { ++i; continue; }
            if (srcC instanceof ConstantMethodref srcCMR) {
                JavaClass owner = this.javaClasses.get(srcCMR.getClass(srcPool).replace('.', '/'));
                if (owner == null) { continue; }
                ConstantMethodref dstCMR = dstPool.getConstant(i);
                ConstantNameAndType srcCNT = srcPool.getConstant(srcCMR.getNameAndTypeIndex());
                ConstantNameAndType dstCNT = dstPool.getConstant(dstCMR.getNameAndTypeIndex());
                dstCNT = new ConstantNameAndType(dstCNT.getNameIndex(), dstCNT.getSignatureIndex());

                String srcDesc = srcCNT.getSignature(srcPool);
                String dstDesc = this.patchDescriptor(srcDesc);
                dstCNT.setSignatureIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));

                String srcName = srcCNT.getName(srcPool);
                Method srcMethod = findMethod(owner, srcName, srcDesc);
                String dstName = this.mappings.get(owner).methodMappings.get(srcMethod);
                if (dstName != null) {
                    dstCNT.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));
                }
                dstCMR.setNameAndTypeIndex(addConstant(dstPool, dstCNT));
            }
            if (srcC instanceof ConstantInterfaceMethodref srcCIMR) {
                JavaClass owner = this.javaClasses.get(srcCIMR.getClass(srcPool).replace('.', '/'));
                if (owner == null) { continue; }
                ConstantInterfaceMethodref dstCIMR = dstPool.getConstant(i);
                ConstantNameAndType srcCNT = srcPool.getConstant(srcCIMR.getNameAndTypeIndex());
                ConstantNameAndType dstCNT = dstPool.getConstant(dstCIMR.getNameAndTypeIndex());
                dstCNT = new ConstantNameAndType(dstCNT.getNameIndex(), dstCNT.getSignatureIndex());

                String srcDesc = srcCNT.getSignature(srcPool);
                String dstDesc = this.patchDescriptor(srcDesc);
                dstCNT.setSignatureIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));

                String srcName = srcCNT.getName(srcPool);
                Method srcMethod = findMethod(owner, srcName, srcDesc);
                String dstName = this.mappings.get(owner).methodMappings.get(srcMethod);
                if (dstName != null) {
                    dstCNT.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));
                }
                dstCIMR.setNameAndTypeIndex(addConstant(dstPool, dstCNT));
            }
        }
        for (int i = 0; i < srcJavaClass.getMethods().length; ++i) {
            Method srcMethod = srcJavaClass.getMethods()[i];
            Method dstMethod = dstJavaClass.getMethods()[i];

            String srcDesc = srcMethod.getSignature();
            String dstDesc = this.patchDescriptor(srcDesc);
            dstMethod.setSignatureIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));

            String srcName = srcMethod.getName();
            String dstName = cm.methodMappings.get(srcMethod);
            if (dstName != null) {
                dstMethod.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));
            }

            for (int x = 0; x < srcMethod.getAttributes().length; ++x) {
                this.obfuscateAttribute(dstJavaClass, srcMethod.getAttributes()[x], dstMethod.getAttributes()[x], o -> true);
            }
        }

        // Others
        for (int i = 1; i < srcPool.getLength(); ++i) {
            var srcC = srcPool.getConstant(i);
            if (srcC instanceof ConstantLong || srcC instanceof ConstantDouble) { ++i; continue; }
            if (srcC instanceof ConstantClass srcCC) {
                String srcName = srcPool.getConstantUtf8(srcCC.getNameIndex()).getBytes().replace('.', '/');
                JavaClass owner = this.javaClasses.get(srcName);
                if (owner == null) { continue; }
                ConstantClass dstCFR = dstPool.getConstant(i);
                String dstName = this.mappings.get(owner).translated;
                // System.out.println(srcName+" -> "+dstName);
                dstCFR.setNameIndex(obfuscateUTF8(dstJavaClass, srcName, dstName, o -> true));/*!(
                    o instanceof ConstantClass cc &&
                    dstPool.getConstantUtf8(cc.getNameIndex()).getBytes().equals(srcName)
                )));*/
            }
            if (srcC instanceof ConstantMethodType srcCMT) {
                ConstantMethodType dstCMT = dstPool.getConstant(i);
                String srcDesc = srcPool.getConstantUtf8(srcCMT.getDescriptorIndex()).getBytes();
                String dstDesc = this.patchDescriptor(srcDesc);
                dstCMT.setDescriptorIndex(obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, o -> true));
            }
        }

        return dstJavaClass;
    }

    static private Field findField(JavaClass javaClass, String name, String descriptor) {
        for (Field f : javaClass.getFields()) {
            if (f.getName().equals(name) && f.getSignature().equals(descriptor)) {
                return f;
            }
        }
        return null;
    }

    private Method findMethod(
        JavaClass javaClass, String name, String descriptor
    ) {
        for (Method m : javaClass.getMethods()) {
            if (m.getName().equals(name) && m.getSignature().equals(descriptor)) {
                return m;
            }
        }
        return null;
    }

    private Pair<JavaClass, Method> findMethodResolved(
        JavaClass javaClass, String name, String descriptor, boolean ignoreFirst
    ) {
        if (!ignoreFirst) {
            for (Method m : javaClass.getMethods()) {
                if (!m.isStatic() && m.getName().equals(name) && m.getSignature().equals(descriptor)) {
                    return Pair.of(javaClass, m);
                }
            }
        }
        JavaClass owner = this.javaClasses.get(javaClass.getSuperclassName().replace(".", "/"));
        if (owner == null) {
            try {
                owner = javaClass.getSuperClass();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (owner != null) {
            var result = findMethodResolved(owner, name, descriptor, false);
            if (result.getValue() != null) { return result; }
        }
        for (String interfaceName : javaClass.getInterfaceNames()) {
            var intface = this.javaClasses.get(interfaceName.replace(".", "/"));
            if (intface != null) {
                var result = findMethodResolved(intface, name, descriptor, false);
                if (result.getValue() != null) { return result; }
            }
        }
        return Pair.of(null, null);
    }

    static int findUTF8Index(ConstantPool pool, String utf8) {
        for (int i = 1; i < pool.getLength(); ++i) {
            var c = pool.getConstant(i);
            if (c instanceof ConstantLong || c instanceof ConstantDouble) { ++i; continue; }
            if (c instanceof ConstantUtf8 u && u.getBytes().equals(utf8)) { return i; }
        }
        return -1;
    }

    static int addConstant(ConstantPool pool, Constant constant) {
        int i = pool.getLength();
        var newPoolArray = Arrays.copyOf(pool.getConstantPool(), i+1);
        newPoolArray[i] = constant;
        pool.setConstantPool(newPoolArray);
        return i;
    }

    static int obfuscateUTF8(
        JavaClass dstJavaClass, String src, String dst,
        Function<Object, Boolean> usedByOther
    ) {
        ConstantPool dstPool = dstJavaClass.getConstantPool();
        int i = findUTF8Index(dstPool, dst);
        if (i == -1) {
            boolean usedByOthers = false;
            i = findUTF8Index(dstPool, src);
            for (var other : getUtf8Usage(dstJavaClass, i)) {
                if (usedByOther.apply(other)) {
                    usedByOthers = true;
                    break;
                }
            }
            if (usedByOthers) {
                i = addConstant(dstPool, new ConstantUtf8(dst));
            }
            else {
                dstPool.setConstant(i, new ConstantUtf8(dst));
            }
        }

        return i;
    }

    void obfuscateAttribute(
        JavaClass dstJavaClass, Attribute srcAttr, Attribute dstAttr,
        Function<Object, Boolean> usedByOthers
    ) {
        if (srcAttr instanceof LocalVariableTable srcLVT) {
            var dstLVT = (LocalVariableTable) dstAttr;
            for (int y = 0; y < srcLVT.getTableLength(); ++y) {
                LocalVariable srcLV = srcLVT.getLocalVariableTable()[y];
                String srcDesc = srcLV.getSignature();
                String dstDesc = this.patchDescriptor(srcDesc);
                int dstDescriptorIndex = obfuscateUTF8(dstJavaClass, srcDesc, dstDesc, usedByOthers);
                dstLVT.getLocalVariableTable()[y].setSignatureIndex(dstDescriptorIndex);
            }
        }
        if (srcAttr instanceof Code srcCode) {
            var dstCode = (Code) dstAttr;
            for (int i = 0; i < srcCode.getAttributes().length; ++i) {
                this.obfuscateAttribute(
                    dstJavaClass, srcCode.getAttributes()[i], dstCode.getAttributes()[i], usedByOthers
                );
            }
        }
    }

    /** Кто может ссылаться на CONSTANT_Utf8_info?
    1.  CONSTANT_Class_info (4.4.1) - имя класса
    2.  CONSTANT_String_info (4.4.3)
    3.  CONSTANT_NameAndType_info (4.4.6) - имя и дескриптор
    4.  CONSTANT_MethodType_info (4.4.9) - дескриптор
    5.  CONSTANT_Module_info (4.4.11) - название
    6.  CONSTANT_Package_info (4.4.12) - название
    7.  Поля (4.5) - имя и дескриптор
    8.  Методы (4.6) - имя и дескриптор
    9.  Все аттрибуты (4.7) - имя аттрибута, + :
        1. InnerClasses (4.7.6) - имя внутреннего (inner) класса
        2. Signature (4.7.9) - сигнатура (дескриптор)
        3. SourceFile (4.7.10) - название файла исходного кода
        4. LocalVariableTable (4.7.13) - имена и дескрипторы локальных переменных
        5. LocalVariableTypeTable (4.7.14)
        6. RuntimeVisibleAnnotations (4.7.16)
        7. MethodParameters (4.7.24)
        8. Module (4.7.25) - module_version_index, requires_version_index
        9. Record (4.7.30) - name_index, descriptor_index
    */
    private static List<Object> getUtf8Usage(JavaClass javaClass, int index) {
        List<Object> result = new ArrayList<>();
        for (var constant : javaClass.getConstantPool()) {
            if (constant instanceof ConstantClass cc && cc.getNameIndex() == index) { result.add(cc); }
            if (constant instanceof ConstantString cs && cs.getStringIndex() == index) { result.add(cs); }
            if (constant instanceof ConstantNameAndType cnt &&
               (cnt.getNameIndex() == index || cnt.getSignatureIndex() == index)) { result.add(cnt); }
            if (constant instanceof ConstantMethodType cmt && cmt.getDescriptorIndex() == index) { result.add(cmt); }
            if (constant instanceof ConstantPackage cmt && cmt.getNameIndex() == index) { result.add(cmt); }
            if (constant instanceof ConstantModule cmt && cmt.getNameIndex() == index) { result.add(cmt); }
        }
        for (var f : javaClass.getFields()) {
            if (f.getNameIndex() == index || f.getSignatureIndex() == index) { result.add(f); }
        }
        for (var m : javaClass.getMethods()) {
            if (m.getNameIndex() == index || m.getSignatureIndex() == index) { result.add(m); }
        }
        return result;
    }

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
                            type = mappings.get(javaClass).translated;
                        }
                        sb.append(type);
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