package com.traktool.mixintale.bootstrap;

public interface ClassTransformer {
    byte[] transform(String name, String transformedName, byte[] bytes);
}
