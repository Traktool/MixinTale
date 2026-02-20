package com.traktool.mixintale.bootstrap;

import com.traktool.mixintale.core.reporting.MixinTaleReportWriter;
import com.traktool.mixintale.core.weaver.MixinTaleCore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;

public final class EarlyMixinTransformer {
    private static final Logger LOGGER = Logger.getLogger(EarlyMixinTransformer.class.getName());

    private final MixinTaleCore core;
    private final Path reportPath;

    public EarlyMixinTransformer() {
        ModJarResourceLookup resourceLookup = new ModJarResourceLookup();
        boolean failHard = Boolean.getBoolean("mixintale.failHard");
        this.core = new MixinTaleCore(resourceLookup, failHard);
        this.reportPath = Path.of("logs", "mixintale-report-" + Instant.now().toEpochMilli() + ".json");
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushReport, "mixintale-report-shutdown"));
    }

    public byte[] transform(String internalClassName, byte[] classBytes) {
        try {
            return core.transform(internalClassName, classBytes);
        } catch (RuntimeException exception) {
            core.report().addError("transform", exception);
            if (Boolean.getBoolean("mixintale.failHard")) throw exception;
            LOGGER.warning("Transform failed for " + internalClassName + ": " + exception.getMessage());
            return classBytes;
        }
    }

    public void flushReport() {
        try {
            new MixinTaleReportWriter().write(reportPath, core.report());
        } catch (Exception exception) {
            LOGGER.warning("Could not write MixinTale report: " + exception.getMessage());
        }
    }
}
