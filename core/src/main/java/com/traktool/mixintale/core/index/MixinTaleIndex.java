package com.traktool.mixintale.core.index;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.traktool.mixintale.core.locate.ResourceLocator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public final class MixinTaleIndex {
    private static final Logger LOGGER = Logger.getLogger(MixinTaleIndex.class.getName());
    private static final Gson GSON = new Gson();

    public record IndexFile(String version, List<PatchDescriptor> patches) {}
    public record PatchDescriptor(String patchClass, String targetClass, int priority, List<ActionDescriptor> actions, String sourceJar) {}
    public record ActionDescriptor(String kind, String methodName, String methodDesc, String targetMethod, String targetDesc,
                                   String owner, String name, String desc, int ordinal, int require) {}

    private final List<PatchDescriptor> patches;

    private MixinTaleIndex(List<PatchDescriptor> patches) {
        this.patches = List.copyOf(patches);
    }

    public static MixinTaleIndex load(ResourceLocator resourceLocator) {
        List<PatchDescriptor> loaded = new ArrayList<>();
        for (String jar : resourceLocator.jars().stream().sorted().toList()) {
            try (var in = resourceLocator.openResource(jar, "mixintale.index.json")) {
                if (in == null) continue;
                IndexFile file = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), IndexFile.class);
                if (file == null || file.patches() == null) continue;
                for (PatchDescriptor patch : file.patches()) {
                    loaded.add(new PatchDescriptor(patch.patchClass(), patch.targetClass(), patch.priority(),
                            patch.actions() == null ? List.of() : patch.actions(), jar));
                }
            } catch (IOException | JsonSyntaxException exception) {
                LOGGER.warning("Unable to load index from " + jar + ": " + exception.getMessage());
            }
        }
        loaded.sort(Comparator
                .comparingInt(PatchDescriptor::priority).reversed()
                .thenComparing(PatchDescriptor::sourceJar)
                .thenComparing(PatchDescriptor::patchClass)
                .thenComparing(PatchDescriptor::targetClass));
        return new MixinTaleIndex(loaded);
    }

    public List<PatchDescriptor> patches() {
        return patches;
    }
}
