package com.traktool.mixintale.core.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MixinTaleReportWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public void write(Path output, MixinTaleApplyReport report) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(report), StandardCharsets.UTF_8);
    }
}
