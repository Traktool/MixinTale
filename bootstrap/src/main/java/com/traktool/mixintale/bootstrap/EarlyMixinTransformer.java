package com.traktool.mixintale.bootstrap;

import com.hypixel.hytale.plugin.early.ClassTransformer;
import com.traktool.mixintale.core.reporting.MixinTaleReportWriter;
import com.traktool.mixintale.core.weaver.MixinTaleCore;

import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.hypixel.hytale.logger.HytaleLogger;

import java.lang.reflect.Method;

public final class EarlyMixinTransformer implements ClassTransformer {
    private static final HytaleLogger LOGGER = HytaleLogger.getLogger();

    private final MixinTaleCore core;
    private final Path reportPath;

    public EarlyMixinTransformer() {
        ModJarResourceLookup resourceLookup = new ModJarResourceLookup();
        boolean failHard = Boolean.getBoolean("mixintale.failHard");
        ensureModJarsOnClasspath(resourceLookup.jars());
        this.core = new MixinTaleCore(resourceLookup, failHard);
        this.reportPath = Path.of("logs", "mixintale-report-" + Instant.now().toEpochMilli() + ".json");
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushReport, "mixintale-report-shutdown"));
    }


    private void ensureModJarsOnClasspath(List<String> jars) {
        ClassLoader loader = getClass().getClassLoader();
        Method addUrl = findAddUrl(loader);
        if (addUrl == null) {
            return;
        }
        for (String jar : jars) {
            try {
                addUrl.invoke(loader, Path.of(jar).toUri().toURL());
            } catch (Exception exception) {
                LOGGER.atWarning().log("Could not add mod jar to classpath: %s (%s)", jar, exception.getMessage());
            }
        }
    }

    private Method findAddUrl(ClassLoader loader) {
        Class<?> type = loader.getClass();
        while (type != null) {
            try {
                Method addUrl = type.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                return addUrl;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
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
