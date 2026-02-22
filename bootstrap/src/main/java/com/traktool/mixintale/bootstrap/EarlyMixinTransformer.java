package com.traktool.mixintale.bootstrap;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import com.hypixel.hytale.plugin.early.EarlyPluginLoader;
import com.traktool.mixintale.core.reporting.MixinTaleReportWriter;
import com.traktool.mixintale.core.weaver.MixinTaleCore;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

public final class EarlyMixinTransformer implements ClassTransformer {
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger();

    private final MixinTaleCore core;
    private final Path reportPath;

    public EarlyMixinTransformer() {
        ModJarResourceLookup resourceLookup = new ModJarResourceLookup();
        boolean failHard = Boolean.getBoolean("mixintale.failHard");
        installModJarsIntoEarlyPluginLoader(resourceLookup.jars());
        this.core = new MixinTaleCore(resourceLookup, failHard);
        this.reportPath = Path.of("logs", "mixintale-report-" + Instant.now().toEpochMilli() + ".json");
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushReport, "mixintale-report-shutdown"));
    }

    private void installModJarsIntoEarlyPluginLoader(List<String> jars) {
        URLClassLoader baseLoader = EarlyPluginLoader.getPluginClassLoader();
        if (baseLoader == null || jars.isEmpty()) {
            return;
        }

        try {
            LinkedHashSet<URL> merged = new LinkedHashSet<>(List.of(baseLoader.getURLs()));
            for (String jar : jars) {
                merged.add(Path.of(jar).toUri().toURL());
            }
            URLClassLoader mergedLoader = new URLClassLoader(merged.toArray(URL[]::new), baseLoader.getParent());

            Field pluginClassLoaderField = EarlyPluginLoader.class.getDeclaredField("pluginClassLoader");
            pluginClassLoaderField.setAccessible(true);
            pluginClassLoaderField.set(null, mergedLoader);
        } catch (Exception exception) {
            LOGGER.atWarning().log("Could not augment EarlyPluginLoader classpath: %s", exception.getMessage());
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        try {
            return core.transform(name, transformedName, bytes);
        } catch (RuntimeException exception) {
            core.report().addError("transform", exception);
            if (Boolean.getBoolean("mixintale.failHard")) {
                throw exception;
            }
            LOGGER.atWarning().log("Transform failed for %s: %s", transformedName, exception.getMessage());
            return bytes;
        }
    }

    public void flushReport() {
        try {
            new MixinTaleReportWriter().write(reportPath, core.report());
        } catch (Exception exception) {
            LOGGER.atWarning().log("Could not write MixinTale report: %s", exception.getMessage());
        }
    }
}
