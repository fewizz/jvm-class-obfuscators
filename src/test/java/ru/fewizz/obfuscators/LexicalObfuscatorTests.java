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
        JavaClass src = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{}).getJavaClass();
        assertTrue(hasUTF8(src, "test/Class"));

        var gen = obf.getObfuscatedClassSupplier(src.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(src);
        assertNotEquals(mapping.translated(), "test.Class");

        JavaClass dst = parseJavaClass(gen.get());
        assertTrue(!hasUTF8(dst, "test/Class"), () -> dst.getConstantPool().toString());
        assertEquals(mapping.translated(), dst.getClassName());
    }

    @Test
    void testSupAndSubClassesNamesObfuscation() throws Exception {
        JavaClass srcSup = new ClassGen("test.SuperClass", "java.lang.Object", null, 0, new String[]{}).getJavaClass();
        JavaClass srcSub = new ClassGen("test.SubClass", srcSup.getClassName(), null, 0, new String[]{}).getJavaClass();
        assertTrue(hasUTF8(srcSup, "test/SuperClass"));
        assertTrue(hasUTF8(srcSub, "test/SubClass"));

        var supGen = obf.getObfuscatedClassSupplier(srcSup.getBytes());
        var subGen = obf.getObfuscatedClassSupplier(srcSub.getBytes());
        obf.onAllClassesProvided();

        ClassMapping supMapping = obf.mappings.get(srcSup);
        ClassMapping subMapping = obf.mappings.get(srcSub);

        assertNotEquals(supMapping.translated(), "test.SuperClass");
        assertNotEquals(subMapping.translated(), "test.SubClass");

        JavaClass dstSup = parseJavaClass(supGen.get());
        JavaClass dstSub = parseJavaClass(subGen.get());
        assertTrue(!hasUTF8(dstSup, "test/SuperClass"));
        assertTrue(!hasUTF8(dstSub, "test/SubClass"));

        assertEquals(supMapping.translated(), dstSup.getClassName());
        assertEquals(subMapping.translated(), dstSub.getClassName());

        assertEquals(supMapping.translated(), dstSub.getSuperclassName());
    }

    @Test
    void testFieldObfuscation() throws Exception {
        ClassGen classGen = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{});
        Field field = new FieldGen(Const.ACC_STATIC | Const.ACC_PRIVATE, Type.CHAR, "fieldName", classGen.getConstantPool()).getField();
        classGen.addField(field);

        JavaClass src = classGen.getJavaClass();
        assertTrue(hasUTF8(src, "test/Class"));
        assertTrue(hasUTF8(src, "fieldName"));

        var classBytesSupplier = obf.getObfuscatedClassSupplier(src.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(src);
        String fieldMapping = mapping.fieldMappings().get(field);
        assertNotEquals(fieldMapping, "fieldName");

        JavaClass dst = parseJavaClass(classBytesSupplier.get());
        assertTrue(!hasUTF8(dst, "test/Class"));
        assertTrue(!hasUTF8(dst, "fieldName"));
        assertTrue(dst.getFields().length == 1);
        assertEquals(fieldMapping, dst.getFields()[0].getName());
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
