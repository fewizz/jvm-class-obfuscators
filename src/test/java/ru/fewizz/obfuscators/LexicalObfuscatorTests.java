package ru.fewizz.obfuscators;

import java.io.ByteArrayInputStream;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.FieldGen;
import org.apache.bcel.generic.Type;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import ru.fewizz.obfuscators.LexicalObfuscator.ClassMapping;

public class LexicalObfuscatorTests {

    private final LexicalObfuscator obf = new LexicalObfuscator();

    private static JavaClass parseJavaClass(byte[] bytes) throws Exception {
        return new ClassParser(new ByteArrayInputStream(bytes), "").parse();
    }

    @Test
    void testClassNameObfuscation() throws Exception {
        JavaClass javaClass = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{}).getJavaClass();

        var classBytesSupplier = obf.getObfuscatedClassSupplier(javaClass.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(javaClass);
        assertNotEquals(mapping.translated(), "test/Class");

        JavaClass obfClass = parseJavaClass(classBytesSupplier.get());
        assertEquals(obfClass.getClassName(), mapping.translated().replace('/', '.'));
    }

    @Test
    void testSupAndSubClassesNamesObfuscation() throws Exception {
        JavaClass supClass = new ClassGen("test.SuperClass", "java.lang.Object", null, 0, new String[]{}).getJavaClass();
        JavaClass subClass = new ClassGen("test.SubClass", supClass.getClassName(), null, 0, new String[]{}).getJavaClass();

        var supClassBytesSupplier = obf.getObfuscatedClassSupplier(supClass.getBytes());
        var subClassBytesSupplier = obf.getObfuscatedClassSupplier(subClass.getBytes());
        obf.onAllClassesProvided();

        ClassMapping supClassMapping = obf.mappings.get(supClass);
        ClassMapping subClassMapping = obf.mappings.get(subClass);

        assertNotEquals(supClassMapping.translated(), "test/SuperClass");
        assertNotEquals(subClassMapping.translated(), "test/SubClass");

        JavaClass obfSupClass = parseJavaClass(supClassBytesSupplier.get());
        JavaClass obfSubClass = parseJavaClass(subClassBytesSupplier.get());

        assertEquals(obfSupClass.getClassName(), supClassMapping.translated().replace('/', '.'));
        assertEquals(obfSubClass.getClassName(), subClassMapping.translated().replace('/', '.'));

        assertEquals(obfSubClass.getSuperclassName(), supClassMapping.translated().replace('/', '.'));
    }

    @Test
    void testFieldObfuscation() throws Exception {
        ClassGen classGen = new ClassGen("test.Class", "java.lang.Object", null, 0, new String[]{});
        Field field = new FieldGen(Const.ACC_STATIC | Const.ACC_PRIVATE, Type.CHAR, "fieldName", classGen.getConstantPool()).getField();
        classGen.addField(field);
        JavaClass javaClass = classGen.getJavaClass();

        var classBytesSupplier = obf.getObfuscatedClassSupplier(javaClass.getBytes());
        obf.onAllClassesProvided();

        ClassMapping mapping = obf.mappings.get(javaClass);
        String fieldMapping = mapping.fieldMappings().get(field);
        assertNotEquals(fieldMapping, "fieldName");

        JavaClass obfClass = parseJavaClass(classBytesSupplier.get());
        assertTrue(obfClass.getFields().length == 1);
        assertEquals(obfClass.getFields()[0].getName(), fieldMapping);
    }

}
