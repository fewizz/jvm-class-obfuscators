package ru.fewizz.obfuscators;

import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import ru.fewizz.Obfuscator;

// Да, на этот раз не Tree API
public class DebugMetadataDeleter extends Obfuscator implements Opcodes {

    @Override
    public Supplier<byte[]> getObfuscatedClassSupplier(byte[] classFileBytes) throws Exception {
        var classWriter = new ClassWriter(0);
        var visitor = new ClassVisitor(ASM9, classWriter) {

            /**
             * Удаляет атрибут SourceFile (4.7.10) и SourceDebugExtension (4.7.11)
             * https://github.com/stephengold/asm/blob/bac9ddeb90c0cbd8b4739fd116bc941c9ab076e1/src/main/java/org/objectweb/asm/ClassWriter.java#L816
             * (а для чего он нужен?)
             */
            @Override
            public void visitSource(String source, String debug) {
                // super.visitSource(source, debug);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    
                    /**
                     * Удаляет атрибут LineNumberTable (4.7.12) атрибута Code
                     */
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        // super.visitLineNumber(line, start);
                    }

                    /**
                     * Удаляет атрибуты LocalVariableTable (4.7.13)
                     * и LocalVariableTypeTable (4.7.14) атрибута Code
                     */
                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                        // super.visitLocalVariable(name, descriptor, signature, start, end, index);
                    }
                };
            }

        };

        new ClassReader(classFileBytes).accept(visitor, 0);
        return () -> classWriter.toByteArray();
    }
    
}
