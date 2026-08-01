package com.gagneflow.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gagneflow.entity.PromptVersion;
import com.gagneflow.service.prompt.PromptExperiment;
import com.gagneflow.service.prompt.PromptMetricsCollector;
import com.gagneflow.service.prompt.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/prompts")
public class PromptAdminController {

    @Autowired
    private PromptRegistry promptRegistry;

    @Autowired
    private PromptExperiment promptExperiment;

    @Autowired
    private PromptMetricsCollector promptMetrics;

    @GetMapping("/{name}")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String name) {
        List<Map<String, Object>> versions = promptRegistry.listVersions(name).stream()
                .map(pv -> Map.<String, Object>of(
                        "versionNumber", pv.getVersionNumber(),
                        "active", pv.isActive(),
                        "description", pv.getDescription() != null ? pv.getDescription() : "",
                        "createdAt", pv.getCreatedAt().toString(),
                        "contentLength", pv.getContent() != null ? pv.getContent().length() : 0
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(versions);
    }

    @GetMapping
    public ResponseEntity<List<String>> listPromptNames() {
        List<String> names = promptRegistry.listVersions("addie_analysis").stream()
                .map(PromptVersion::getPromptName)
                .distinct()
                .collect(Collectors.toList());
        // 补充：扫描所有已知 prompt 名称
        return ResponseEntity.ok(names.isEmpty()
                ? List.of("addie_analysis", "addie_design", "addie_development", "addie_review")
                : names);
    }

    @PostMapping("/{name}/{version}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            @PathVariable String name, @PathVariable int version) {
        PromptVersion activated = promptRegistry.activate(name, version);
        return ResponseEntity.ok(Map.of(
                "promptName", activated.getPromptName(),
                "versionNumber", activated.getVersionNumber(),
                "active", activated.isActive(),
                "message", "Prompt " + name + " v" + version + " 已激活"
        ));
    }

    @GetMapping("/{name}/compare")
    public ResponseEntity<PromptMetricsCollector.PromptComparison> compare(
            @PathVariable String name,
            @RequestParam("v1") int versionA,
            @RequestParam("v2") int versionB) {
        return ResponseEntity.ok(promptMetrics.compare(name, versionA, versionB));
    }

    @GetMapping("/{name}/stats")
    public ResponseEntity<Map<Integer, PromptMetricsCollector.VersionStats>> getStats(
            @PathVariable String name) {
        return ResponseEntity.ok(promptMetrics.getStats(name));
    }

    @GetMapping("/experiment/status")
    public ResponseEntity<Map<String, Object>> experimentStatus() {
        return ResponseEntity.ok(Map.of(
                "enabled", promptExperiment.isEnabled(),
                "splits", promptExperiment.getSplits()
        ));
    }
}
