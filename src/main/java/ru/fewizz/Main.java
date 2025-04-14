package ru.fewizz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import ru.fewizz.obfuscators.ControlFlowObfuscator;
import ru.fewizz.obfuscators.InvokeDynamicStringConstantsObfuscator;
import ru.fewizz.obfuscators.LexicalObfuscator;
import ru.fewizz.obfuscators.NaiveStringConstantsObfuscator;

public class Main {
    
    public static void main(String[] args) throws Exception {
        String type = args[0];
        args = Arrays.copyOfRange(args, 1, args.length);

        Obfuscator obfuscator;

        switch (type) {
            case "control-flow":
                obfuscator = new ControlFlowObfuscator();
                break;
            case "string-constants-naive":
                obfuscator = new NaiveStringConstantsObfuscator();
                break;
            case "string-constants-invoke-dynamic":
                obfuscator = new InvokeDynamicStringConstantsObfuscator();
                break;
            case "lexical":
                obfuscator = new LexicalObfuscator();
                break;
            default:
                throw new RuntimeException("Unknown obfuscator: \""+type+"\"");
        }

        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);

        // Если на вход подается путь до файла,
        // то обрабатывается только один файл
        if (!Files.isDirectory(src)) {
            handleFile(src, dst, obfuscator);
        }
        // Рекурсивно обрабатываются все файлы в исходной директории
        else {
            Files.walk(src).forEach(srcFile -> {
                if (Files.isDirectory(srcFile))
                    return;
                handleFile(srcFile, dst, obfuscator);
            });
        }

        obfuscator.end();
    }

    private static void handleFile(
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

            // 2. Обработка класса
            var future = obfuscator.transform(classFileBytes);

            // 3. Запись байтов класс-файла в файл назначения
            future.thenAccept(bytesAndPath -> {
                var bytes = bytesAndPath.getLeft();
                var path = bytesAndPath.getRight();
                try {
                    Files.write(dst.resolve(path.replace('.', '/').concat(".class")), bytes);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
