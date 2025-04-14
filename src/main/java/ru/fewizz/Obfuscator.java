package ru.fewizz;

import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.tuple.Pair;

abstract public class Obfuscator {

    public abstract CompletableFuture<Pair<byte[], String>>
    transform(byte[] classFileBytes) throws Exception;

    public void end() throws Exception {}

}
