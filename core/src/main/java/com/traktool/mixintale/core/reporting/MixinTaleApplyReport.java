package com.traktool.mixintale.core.reporting;

import java.time.Instant;
import java.util.*;

public final class MixinTaleApplyReport {
    public final Map<String, Object> meta = new LinkedHashMap<>();
    public final Map<String, Object> indexSummary = new LinkedHashMap<>();
    public final List<Map<String, Object>> mixinConfigs = new ArrayList<>();
    public final List<Map<String, Object>> patches = new ArrayList<>();
    public final List<Map<String, Object>> callsites = new ArrayList<>();
    public final List<Map<String, Object>> collisions = new ArrayList<>();
    public final List<Map<String, Object>> errors = new ArrayList<>();

    public MixinTaleApplyReport() {
        meta.put("createdAt", Instant.now().toString());
    }

    public void addError(String phase, Throwable throwable) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        row.put("type", throwable.getClass().getName());
        row.put("message", throwable.getMessage());
        errors.add(row);
    }
}
