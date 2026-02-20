package com.traktool.mixintale.core.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.util.HashSet;
import java.util.Set;

public final class SafeClassWriter extends ClassWriter {
    private final ClassInfoResolver resolver;

    public SafeClassWriter(ClassReader classReader, int flags, ClassInfoResolver resolver) {
        super(classReader, flags);
        this.resolver = resolver;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
            return "java/lang/Object";
        }
        if (isAssignableFrom(type1, type2)) return type1;
        if (isAssignableFrom(type2, type1)) return type2;

        Set<String> visited = new HashSet<>();
        String cursor = type1;
        while (cursor != null && visited.add(cursor)) {
            if (isAssignableFrom(cursor, type2)) {
                return cursor;
            }
            ClassInfo info = resolver.resolve(cursor);
            cursor = info.superName();
        }
        return "java/lang/Object";
    }

    private boolean isAssignableFrom(String parent, String child) {
        if (parent.equals(child)) return true;
        Set<String> visited = new HashSet<>();
        String cursor = child;
        while (cursor != null && visited.add(cursor)) {
            ClassInfo info = resolver.resolve(cursor);
            if (info.interfaces().contains(parent) || parent.equals(info.superName())) return true;
            if (info.interfaces().stream().anyMatch(parent::equals)) return true;
            cursor = info.superName();
        }
        return false;
    }
}
