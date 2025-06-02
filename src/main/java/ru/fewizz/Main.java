package ru.fewizz;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.apache.bcel.classfile.ClassParser;

public class Main {
    private static final Logger LOGGER = Logger.getLogger("obfuscator");

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path src = Paths.get(args[0]);
        Path dst = Paths.get(args[1]);

        LOGGER.info("loading obfuscators classes");
        List<Class<Obfuscator>> obfuscatorClasses = new ArrayList<>();
        Iterable<URL> iter = () -> Main.class.getClassLoader().resources("META-INF/obfuscators").iterator();
        for (var url : iter) {
            try(Scanner scan = new Scanner(url.openStream())) {
                while (scan.hasNextLine()) {
                    var line = scan.nextLine();
                    obfuscatorClasses.add((Class<Obfuscator>) Class.forName(line));
                }
            }
        }

        LOGGER.info("creating selected obfuscators");
        List<Obfuscator> obfuscators = new ArrayList<>();
        for (var obfuscatorName : Arrays.copyOfRange(args, 2, args.length)) {
            Class<Obfuscator> obfuscatorClass = obfuscatorClasses.stream()
                .filter(c -> c.getName().equals(obfuscatorName))
                .findFirst().get();
            obfuscators.add(obfuscatorClass.getConstructor().newInstance());
        }

        LOGGER.info("loading class files");
        List<byte[]> classesBytes = new ArrayList<>();

        // Если на вход подается путь до файла,
        // то обрабатывается только один файл
        if (!Files.isDirectory(src)) {
            classesBytes.add(Files.readAllBytes(src));
        }
        // Рекурсивно обрабатываются все файлы в исходной директории
        else {
            Files.walk(src).forEach(srcFile -> {
                if (Files.isDirectory(srcFile)) return;
                try {
                    classesBytes.add(Files.readAllBytes(srcFile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        for (var obfuscator : obfuscators) {
            LOGGER.info("obfuscating class files with " + obfuscator.getClass().getName());

            List<Supplier<byte[]>> obfuscatedClassBytesSuppliers = new ArrayList<>();
            for (var classFile : classesBytes) {
                obfuscatedClassBytesSuppliers.add(obfuscator.getObfuscatedClassSupplier(classFile));
            }
            obfuscator.onAllClassesProvided();
            classesBytes.clear();
            for (var supplier : obfuscatedClassBytesSuppliers) {
                classesBytes.add(supplier.get());
            }
        }

        // 3. Запись байтов класс-файла в файл назначения
        LOGGER.info("writing obfuscated classes");
        for (var classBytes : classesBytes) {
            try {
                // Костыль - парсим класс еще раз, чтобы получить (вероятно) обфусцированное имя класса
                var inputStream = new ByteArrayInputStream(classBytes);
                var parser = new ClassParser(inputStream, "");
                var javaClass = parser.parse();
                var dstPath = dst.resolve(javaClass.getClassName().replace('.', '/').concat(".class"));
                Files.createDirectories(dstPath.getParent());
                Files.write(dstPath, classBytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
