package com.traktool.mixintale.bootstrap;

import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.traktool.mixintale.core.locate.ResourceLocator;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

public final class ModJarResourceLookup implements ResourceLocator {
    private final List<Path> jars;
    private final Map<String, Path> cache = new ConcurrentHashMap<>();

    public ModJarResourceLookup() {
        this(resolveModsPath());
    }

    public ModJarResourceLookup(Path modsPath) {
        try (var stream = Files.list(modsPath)) {
            this.jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list mods directory: " + modsPath, e);
        }
    }

    private static Path resolveModsPath() {
        String override = System.getProperty("mixintale.modsDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return PluginManager.MODS_PATH;
    }

    @Override
    public List<String> jars() {
        return jars.stream().map(Path::toString).toList();
    }

    @Override
    public InputStream openResource(String jar, String path) throws IOException {
        Path jarPath = Path.of(jar);
        Path cached = cache.get(path);
        if (cached != null && cached.equals(jarPath)) {
            return openFromJar(jarPath, path);
        }
        InputStream candidate = openFromJar(jarPath, path);
        if (candidate != null) {
            cache.put(path, jarPath);
        }
        return candidate;
    }

    private InputStream openFromJar(Path jarPath, String path) throws IOException {
        JarFile jarFile = new JarFile(jarPath.toFile());
        var entry = jarFile.getJarEntry(path);
        if (entry == null) {
            jarFile.close();
            return null;
        }
        InputStream stream = jarFile.getInputStream(entry);
        return new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    jarFile.close();
                }
            }
        };
    }
}
