package ru.fewizz;

import java.util.function.Supplier;

abstract public class Obfuscator {

    public abstract Supplier<byte[]> transform(byte[] classFileBytes) throws Exception;

    public void end() throws Exception {}

}
