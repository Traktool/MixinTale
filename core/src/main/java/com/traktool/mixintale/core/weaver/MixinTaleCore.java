package com.traktool.mixintale.core.weaver;

import com.traktool.mixintale.core.asm.BytecodeClassInfoResolver;
import com.traktool.mixintale.core.index.MixinTaleIndex;
import com.traktool.mixintale.core.locate.ResourceLocator;
import com.traktool.mixintale.core.reporting.MixinTaleApplyReport;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MixinTaleCore {
    private final ResourceLocator resourceLocator;
    private final boolean failHard;
    private final MixinTaleIndex index;
    private final BytecodeClassInfoResolver resolver;
    private final MixinTaleWeaver weaver = new MixinTaleWeaver();
    private final MixinTaleApplyReport report = new MixinTaleApplyReport();

    public MixinTaleCore(ResourceLocator resourceLocator, boolean failHard) {
        this.resourceLocator = resourceLocator;
        this.failHard = failHard;
        this.index = MixinTaleIndex.load(resourceLocator);
        this.resolver = new BytecodeClassInfoResolver(resourceLocator);
        report.meta.put("modsJars", resourceLocator.jars());
        report.indexSummary.put("patches", index.patches().size());
    }

    public byte[] transform(String internalClassName, byte[] classBytes) {
        List<MixinTaleIndex.PatchDescriptor> candidates = index.patches().stream()
                .filter(p -> p.targetClass().replace('.', '/').equals(internalClassName))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return classBytes;
        registerCollisions(candidates);
        return weaver.weave(classBytes, internalClassName, candidates, resolver, report, failHard);
    }

    private void registerCollisions(List<MixinTaleIndex.PatchDescriptor> candidates) {
        Map<String, List<MixinTaleIndex.PatchDescriptor>> byTarget = candidates.stream()
                .collect(Collectors.groupingBy(c -> c.targetClass()));
        for (Map.Entry<String, List<MixinTaleIndex.PatchDescriptor>> entry : byTarget.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            var sorted = entry.getValue().stream().sorted(java.util.Comparator
                    .comparingInt(MixinTaleIndex.PatchDescriptor::priority).reversed()
                    .thenComparing(MixinTaleIndex.PatchDescriptor::sourceJar)
                    .thenComparing(MixinTaleIndex.PatchDescriptor::patchClass)).toList();
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("target", entry.getKey());
            row.put("winner", sorted.get(0).patchClass());
            row.put("losers", sorted.stream().skip(1).map(MixinTaleIndex.PatchDescriptor::patchClass).toList());
            report.collisions.add(row);
        }
    }

    public MixinTaleApplyReport report() {
        return report;
    }
}
