package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.FieldGen;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.Type;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import ru.fewizz.obfuscators.LexicalObfuscator.ClassMapping;

public class LexicalObfuscatorTests {

    private final LexicalObfuscator obf = new LexicalObfuscator();

    private static JavaClass parseJavaClass(byte[] bytes) throws Exception {
        return new ClassParser(new ByteArrayInputStream(bytes), "").parse();
    }

    private static boolean hasUTF8(JavaClass javaClass, String str) {
        for (var c : javaClass.getConstantPool()) {
            if (c instanceof ConstantUtf8 utf && utf.getBytes().equals(str)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void testClassNameObfuscation() throws Exception {
        // Создаем класс test.Class
        JavaClass src = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{}).getJavaClass();
        // Проверяем что в пуле констант есть строка с именем класса
        assertTrue(hasUTF8(src, "test/Class"));

        // Получаем Supplier байтов обфусцированного класса
        var dstBytes = obf.getObfuscatedClassSupplier(src.getBytes());
        // Сообщаем что классов для обфускации больше не будет
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(src);
        // Проверяем что есть маппинг на изменение имени класса
        assertNotEquals(mapping.translated(), "test.Class");

        // Получаем обфусцированный класс
        JavaClass dst = parseJavaClass(dstBytes.get());
        // Проверяем что исходного имени класса нет в пуле констант
        assertTrue(!hasUTF8(dst, "test/Class"), () -> dst.getConstantPool().toString());
        // Проверяем что класс был переименован согласно маппингу
        assertEquals(mapping.translated(), dst.getClassName());
    }

    @Test
    void testSupAndSubClassesNamesObfuscation() throws Exception {
        // Создаем класс test.SuperClass
        JavaClass srcSup = new ClassGen("test.SuperClass", "java.lang.Object", null, 0, new String[]{}).getJavaClass();
        assertTrue(hasUTF8(srcSup, "test/SuperClass"));

        // Создаем класс test.SubClass, наследуемый от test.SuperClass
        JavaClass srcSub = new ClassGen("test.SubClass", srcSup.getClassName(), null, 0, new String[]{}).getJavaClass();
        assertTrue(hasUTF8(srcSub, "test/SubClass"));

        var dstBytesSup = obf.getObfuscatedClassSupplier(srcSup.getBytes());
        var dstBytesSub = obf.getObfuscatedClassSupplier(srcSub.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mappingSup = obf.mappings.get(srcSup);
        ClassMapping mappingSub = obf.mappings.get(srcSub);

        assertNotEquals(mappingSup.translated(), "test.SuperClass");
        assertNotEquals(mappingSub.translated(), "test.SubClass");

        JavaClass dstSup = parseJavaClass(dstBytesSup.get());
        JavaClass dstSub = parseJavaClass(dstBytesSub.get());
        assertTrue(!hasUTF8(dstSup, "test/SuperClass"));
        assertTrue(!hasUTF8(dstSub, "test/SubClass"));

        assertEquals(mappingSup.translated(), dstSup.getClassName());
        assertEquals(mappingSub.translated(), dstSub.getClassName());

        // Проверяем что имя суперкласса в подклассе был обфусцирован правильно
        assertEquals(mappingSup.translated(), dstSub.getSuperclassName());
    }

    @Test
    void testFieldObfuscation() throws Exception {
        // Создаем класс с полем static private char[] fieldName
        ClassGen classGen = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{});
        Field field = new FieldGen(Const.ACC_STATIC | Const.ACC_PRIVATE, Type.CHAR, "fieldName", classGen.getConstantPool()).getField();
        classGen.addField(field);

        JavaClass src = classGen.getJavaClass();
        assertTrue(hasUTF8(src, "test/Class"));
        assertTrue(hasUTF8(src, "fieldName"));

        var dstBytes = obf.getObfuscatedClassSupplier(src.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(src);
        String fieldMapping = mapping.fieldMappings().get(field);
        // Проверяем что для поля был создан маппинг
        assertNotEquals(fieldMapping, "fieldName");

        JavaClass dst = parseJavaClass(dstBytes.get());
        assertTrue(!hasUTF8(dst, "test/Class"));
        assertTrue(!hasUTF8(dst, "fieldName"));
        assertTrue(dst.getFields().length == 1);
        // Проверяем что имя поля было обфусцировано согласно маппингу
        assertEquals(fieldMapping, dst.getFields()[0].getName());
    }

    @Test
    void testHiddenFieldObfuscation() throws Exception {
        // Создаем класс A с полем fieldName
        ClassGen genA = new ClassGen("A", "java.lang.Object", null, 0, new String[]{});
        Field fieldA = new FieldGen(Const.ACC_STATIC | Const.ACC_PRIVATE, Type.CHAR, "fieldName", genA.getConstantPool()).getField();
        genA.addField(fieldA);
        JavaClass srcA = genA.getJavaClass();
        var bytesA = obf.getObfuscatedClassSupplier(srcA.getBytes());

        assertTrue(hasUTF8(srcA, "A"));
        assertTrue(hasUTF8(srcA, "fieldName"));

        // Создаем класс B, наследуемый от A и с таким же полем fieldName
        // В данном случае поля в двух классах никак не свзяаны
        // JLS 8.3:
        // If the class declares a field with a certain name, then the declaration
        // of that field is said to hide any and all accessible declarations of
        // fields with the same name in superclasses, and superinterfaces of the class.
        ClassGen genB = new ClassGen("B", srcA.getClassName(), null, 0, new String[]{});
        Field fieldB = new FieldGen(Const.ACC_STATIC | Const.ACC_PRIVATE, Type.CHAR, "fieldName", genB.getConstantPool()).getField();
        genB.addField(fieldB);
        JavaClass srcB = genB.getJavaClass();
        var bytesB = obf.getObfuscatedClassSupplier(srcB.getBytes());

        assertTrue(hasUTF8(srcB, "B"));
        assertTrue(hasUTF8(srcB, "fieldName"));

        obf.onAllClassesProvided();

        ClassMapping mappingA = obf.mappings.get(srcA);
        String fieldMappingA = mappingA.fieldMappings().get(fieldA);
        assertNotEquals(fieldMappingA, "fieldName");

        ClassMapping mappingB = obf.mappings.get(srcB);
        String fieldMappingB = mappingB.fieldMappings().get(fieldB);
        assertNotEquals(fieldMappingB, "fieldName");

        assertNotEquals(fieldMappingA, fieldMappingB);

        JavaClass dstA = parseJavaClass(bytesA.get());
        assertTrue(!hasUTF8(dstA, "A"));
        assertTrue(!hasUTF8(dstA, "fieldName"));
        assertTrue(dstA.getFields().length == 1);
        assertEquals(fieldMappingA, dstA.getFields()[0].getName());

        JavaClass dstB = parseJavaClass(bytesB.get());
        assertTrue(!hasUTF8(dstB, "B"));
        assertTrue(!hasUTF8(dstB, "fieldName"));
        assertTrue(dstB.getFields().length == 1);
        assertEquals(fieldMappingB, dstB.getFields()[0].getName());

        // Обфусцированные имена полей должны отличаться
        assertNotEquals(dstA.getFields()[0].getName(), dstB.getFields()[0].getName());
    }

    @Test
    void testMethodObfuscation() throws Exception {
        ClassGen classGen = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{});
        Method method = new MethodGen(
            Const.ACC_ABSTRACT, Type.CHAR,
            new Type[]{}, new String[]{},
            "methodName", null, null, classGen.getConstantPool()
        ).getMethod();
        classGen.addMethod(method);

        JavaClass src = classGen.getJavaClass();
        assertTrue(hasUTF8(src, "test/Class"));
        assertTrue(hasUTF8(src, "methodName"));

        var classBytesSupplier = obf.getObfuscatedClassSupplier(src.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(src);
        String methodMapping = mapping.methodMappings().get(method);
        assertNotEquals(methodMapping, "fieldName");

        JavaClass dst = parseJavaClass(classBytesSupplier.get());
        assertTrue(!hasUTF8(dst, "test/Class"));
        assertTrue(!hasUTF8(dst, "methodName"));
        assertTrue(dst.getMethods().length == 1);
        assertEquals(methodMapping, dst.getMethods()[0].getName());
    }

}
