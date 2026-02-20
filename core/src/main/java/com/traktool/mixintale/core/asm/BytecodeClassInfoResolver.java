package com.traktool.mixintale.core.asm;

import com.traktool.mixintale.core.locate.ResourceLocator;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BytecodeClassInfoResolver implements ClassInfoResolver {
    private final ResourceLocator resourceLocator;
    private final Map<String, ClassInfo> cache = new ConcurrentHashMap<>();

    public BytecodeClassInfoResolver(ResourceLocator resourceLocator) {
        this.resourceLocator = resourceLocator;
    }

    @Override
    public ClassInfo resolve(String internalName) {
        return cache.computeIfAbsent(internalName, this::readClassInfo);
    }

    private ClassInfo readClassInfo(String internalName) {
        try (InputStream in = resourceLocator.openClass(internalName)) {
            if (in == null) {
                return new ClassInfo(internalName, "java/lang/Object", Set.of(), false);
            }
            ClassReader reader = new ClassReader(in);
            boolean isInterface = (reader.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
            return new ClassInfo(reader.getClassName(), reader.getSuperName(), Set.of(reader.getInterfaces()), isInterface);
        } catch (IOException exception) {
            return new ClassInfo(internalName, "java/lang/Object", Set.of(), false);
        }
    }
}
