## 决策类型
- PLAN: 继续分析和规划任务步骤
- EXECUTE: 需要执行具体步骤，由 Executor Agent 调用工具
- FETCH: 需要从知识库检索教学资料、课程标准、教案模板
- REVIEW: 教案草稿已完成，请求 Review Agent 质量审查
- FINISH: 审查通过后的最终输出（必须先经过 REVIEW）。如果工具连续3次返回空结果，也必须 FINISH 并如实报告无法完成的原因和具体缺失的数据。
