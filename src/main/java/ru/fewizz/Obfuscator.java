package ru.fewizz;

abstract public class Obfuscator {

    abstract public byte[] transform(byte[] classFileBytes) throws Exception;

}
