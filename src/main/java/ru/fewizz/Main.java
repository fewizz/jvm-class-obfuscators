package ru.fewizz;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.apache.bcel.classfile.ClassParser;

import ru.fewizz.obfuscators.ControlFlowObfuscator;
import ru.fewizz.obfuscators.InvokeDynamicStringConstantsObfuscator;
import ru.fewizz.obfuscators.LexicalObfuscator;
import ru.fewizz.obfuscators.NaiveStringConstantsObfuscator;

public class Main {

    public static void main(String[] args) throws Exception {
        String type = args[0];
        args = Arrays.copyOfRange(args, 1, args.length);

        Obfuscator obfuscator = switch (type) {
            case "control-flow" -> new ControlFlowObfuscator();
            case "string-constants-naive" -> new NaiveStringConstantsObfuscator();
            case "string-constants-invoke-dynamic" -> new InvokeDynamicStringConstantsObfuscator();
            case "lexical" -> new LexicalObfuscator();
            default -> throw new RuntimeException("Unknown obfuscator: \"" + type + "\"");
        };

        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);

        List<Supplier<byte[]>> suppliers = new ArrayList<>();

        // Если на вход подается путь до файла,
        // то обрабатывается только один файл
        if (!Files.isDirectory(src)) {
            suppliers.add(handleFile(src, dst, obfuscator));
        }
        // Рекурсивно обрабатываются все файлы в исходной директории
        else {
            Files.walk(src).forEach(srcFile -> {
                if (Files.isDirectory(srcFile))
                    return;
                suppliers.add(handleFile(srcFile, dst, obfuscator));
            });
        }

        obfuscator.onAllClassesProvided();

        // 3. Запись байтов класс-файла в файл назначения
        for (var s : suppliers) {
            byte[] transformedClassBytes = s.get();
            try {
                // Костыль - парсим класс еще раз, чтобы получить (вероятно) обфусцированное имя класса
                var inputStream = new ByteArrayInputStream(transformedClassBytes);
                var parser = new ClassParser(inputStream, "");
                var javaClass = parser.parse();
                var dstPath = dst.resolve(javaClass.getClassName().replace('.', '/').concat(".class"));
                Files.createDirectories(dstPath.getParent());
                Files.write(dstPath, transformedClassBytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Supplier<byte[]> handleFile(
        Path srcFile,
        Path dst,
        Obfuscator obfuscator
    ) {
         try {
            // Создание директории назначения
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }

            // 1. Чтение байтов исходного класс-файла
            byte[] classFileBytes = Files.readAllBytes(srcFile);

            // 2. Планирование обработки класса
            return obfuscator.getObfuscatedClassSupplier(classFileBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
