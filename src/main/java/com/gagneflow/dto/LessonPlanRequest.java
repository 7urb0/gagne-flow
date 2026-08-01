package com.gagneflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public class LessonPlanRequest {
    @NotBlank(message="\u5b66\u6bb5\u4e0d\u80fd\u4e3a\u7a7a")
    @Pattern(regexp="\u5c0f\u5b66|\u521d\u4e2d|\u9ad8\u4e2d", message="\u5b66\u6bb5\u5fc5\u987b\u4e3a\u5c0f\u5b66/\u521d\u4e2d/\u9ad8\u4e2d")
    private @NotBlank(message="\u5b66\u6bb5\u4e0d\u80fd\u4e3a\u7a7a") @Pattern(regexp="\u5c0f\u5b66|\u521d\u4e2d|\u9ad8\u4e2d", message="\u5b66\u6bb5\u5fc5\u987b\u4e3a\u5c0f\u5b66/\u521d\u4e2d/\u9ad8\u4e2d") String stage;
    @Min(value=1L, message="\u5e74\u7ea7\u6700\u5c0f\u4e3a1")
    @Max(value=12L, message="\u5e74\u7ea7\u6700\u5927\u4e3a12")
    private @Min(value=1L, message="\u5e74\u7ea7\u6700\u5c0f\u4e3a1") @Max(value=12L, message="\u5e74\u7ea7\u6700\u5927\u4e3a12") int grade;
    @NotBlank(message="\u5b66\u79d1\u4e0d\u80fd\u4e3a\u7a7a")
    private @NotBlank(message="\u5b66\u79d1\u4e0d\u80fd\u4e3a\u7a7a") String subject;
    @Min(value=1L, message="\u8bfe\u65f6\u81f3\u5c11\u4e3a1")
    @Max(value=20L, message="\u8bfe\u65f6\u6700\u591a\u4e3a20")
    private @Min(value=1L, message="\u8bfe\u65f6\u81f3\u5c11\u4e3a1") @Max(value=20L, message="\u8bfe\u65f6\u6700\u591a\u4e3a20") int hours;
    @NotBlank(message="\u6559\u5b66\u76ee\u6807\u4e0d\u80fd\u4e3a\u7a7a")
    @Size(min=2, max=500, message="\u6559\u5b66\u76ee\u6807\u957f\u5ea62-500\u5b57")
    private @NotBlank(message="\u6559\u5b66\u76ee\u6807\u4e0d\u80fd\u4e3a\u7a7a") @Size(min=2, max=500, message="\u6559\u5b66\u76ee\u6807\u957f\u5ea62-500\u5b57") String goals;
    private String mode = "quick";
    private List<String> uploadedFileNames;

    public String getStage() {
        return this.stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public int getGrade() {
        return this.grade;
    }

    public void setGrade(int grade) {
        this.grade = grade;
    }

    public String getSubject() {
        return this.subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public int getHours() {
        return this.hours;
    }

    public void setHours(int hours) {
        this.hours = hours;
    }

    public String getGoals() {
        return this.goals;
    }

    public void setGoals(String goals) {
        this.goals = goals;
    }

    public String getMode() {
        return this.mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<String> getUploadedFileNames() {
        return this.uploadedFileNames;
    }

    public void setUploadedFileNames(List<String> names) {
        this.uploadedFileNames = names;
    }
}
