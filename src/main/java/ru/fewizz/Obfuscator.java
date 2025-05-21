package ru.fewizz;

import java.util.function.Supplier;

abstract public class Obfuscator {

    /**
     * @param classFileBytes Байты класс-файла, который нужно обфусцировать
     * @return Получаемый <code>Supplier</code> будет вызыван один раз, после
     *  события <code>onAllClassesProvided</code>
     */
    public abstract Supplier<byte[]> getObfuscatedClassSupplier(
        byte[] classFileBytes
    ) throws Exception;

    /**
     * Вызывается когда обфускатору предуставлены все класс-файлы.
     */
    public void onAllClassesProvided() throws Exception {}

}
