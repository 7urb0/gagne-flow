package com.gagneflow.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="gagneflow.pipeline")
public class PipelineStageConfig {
    private List<String> stages = new ArrayList<String>(List.of("analysis", "design", "development", "review", "format"));

    public List<String> getStages() {
        return this.stages;
    }

    public void setStages(List<String> stages) {
        this.stages = stages;
    }
}
