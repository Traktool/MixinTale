package com.traktool.mixintale.core.asm;

import java.util.Set;

public record ClassInfo(String internalName, String superName, Set<String> interfaces, boolean isInterface) {
}
