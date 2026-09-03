package com.gagneflow.service.lesson;

/**
 * 教案教学基本信息（2026-09-02 教案结构改造）：
 * 由表单数据填充（非 LLM 生成），Format 阶段渲染为教案头部的课题与元信息行。
 *
 * @param topic   课题名（表单可选字段；为空时头部回退为 "{学科}{年级}教案"）
 * @param stage   学段（小学/初中/高中）
 * @param grade   年级（1-12）
 * @param subject 学科
 * @param hours   课时数
 */
public record LessonHeader(String topic, String stage, int grade, String subject, int hours) {

    /** h1 主标题：有课题用课题，否则回退 "{学科}{年级}教案"，再否则 "教案" */
    public String title() {
        if (topic != null && !topic.isBlank()) {
            return topic.trim();
        }
        if (subject != null && !subject.isBlank()) {
            return subject + grade + "教案";
        }
        return "教案";
    }

    /** 元信息行（非空段才展示）：小学 · 四年级 · 数学 · 共 1 课时 */
    public String metaLine() {
        StringBuilder sb = new StringBuilder();
        appendMeta(sb, stage);
        if (grade > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(grade).append("年级");
        }
        appendMeta(sb, subject);
        if (hours > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("共 ").append(hours).append(" 课时");
        }
        return sb.toString();
    }

    private void appendMeta(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(part.trim());
        }
    }
}
