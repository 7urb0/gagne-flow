package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class K12CurriculumLoader {
    private static final Logger logger = LoggerFactory.getLogger(K12CurriculumLoader.class);
    @Value(value="${gagneflow.k12.path:lesson-plan-docs/k12_curriculum.json}")
    private String k12Path;
    private JsonNode root;

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(this.k12Path, new String[0]);
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            this.root = mapper.readTree(json);
            logger.info("K12 \u8bfe\u7a0b\u6807\u51c6\u6570\u636e\u52a0\u8f7d\u5b8c\u6210\uff0c\u8def\u5f84: {}", (Object)this.k12Path);
        }
        catch (Exception e) {
            logger.error("K12 \u8bfe\u7a0b\u6807\u51c6\u6570\u636e\u52a0\u8f7d\u5931\u8d25: {}, \u8def\u5f84: {}", (Object)e.getMessage(), (Object)this.k12Path);
            this.root = null;
        }
    }

    public String lookup(String stage, String grade, String subject) {
        if (this.root == null) {
            return "K12 \u8bfe\u7a0b\u6807\u51c6\u6570\u636e\u672a\u52a0\u8f7d";
        }
        StringBuilder result = new StringBuilder();
        result.append("K12 \u8bfe\u7a0b\u6807\u51c6\u67e5\u8be2\u7ed3\u679c\uff1a\n");
        for (JsonNode stageNode : this.root.path("\u5b66\u6bb5")) {
            if (stage != null && !stageNode.path("name").asText().contains(stage)) continue;
            for (JsonNode subjectNode : stageNode.path("\u5b66\u79d1")) {
                if (subject != null && !subjectNode.path("name").asText().contains(subject)) continue;
                result.append("\n\u3010").append(stageNode.path("name").asText()).append(" - ").append(subjectNode.path("name").asText()).append("\u3011\n");
                for (JsonNode gradeNode : subjectNode.path("\u5e74\u7ea7")) {
                    if (grade != null && !gradeNode.path("grade").asText().contains(grade)) continue;
                    result.append("  ").append(gradeNode.path("grade").asText()).append(":\n");
                    for (JsonNode chapter : gradeNode.path("\u7ae0\u8282")) {
                        result.append("    - ").append(chapter.path("name").asText());
                        if (chapter.has("\u77e5\u8bc6\u70b9")) {
                            result.append(" (");
                            ArrayList points = new ArrayList();
                            chapter.path("\u77e5\u8bc6\u70b9").forEach(p -> points.add(p.asText()));
                            result.append(String.join((CharSequence)"\u3001", points));
                            result.append(")");
                        }
                        result.append("\n");
                    }
                }
            }
        }
        return result.toString();
    }

    public boolean isLoaded() {
        return this.root != null;
    }
}
