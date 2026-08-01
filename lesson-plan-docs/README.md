# 教案生成系统 - 教学知识库

本目录存放教学领域的参考文档，通过 RAG 向量检索为 GagneFlow 提供教育领域知识。

## 📁 目录结构

```
lesson-plan-docs/
├── curriculum_standards.md    # 课程标准与教学大纲
├── lesson_templates.md         # 教案模板与设计指南
├── teaching_strategies.md      # 教学方法与策略
└── assessment_rubrics.md       # 评价量规与考核标准
```

## 使用方法

1. 将教学文档（.md / .txt / .pdf / .docx）放入本目录
2. 启动 GagneFlow，访问 `http://localhost:9900`
3. 通过前端上传文件或运行 `make upload` 自动向量化
4. 文档索引完成后，Agent 可通过 `queryInternalDocs` 工具检索教学内容

## K12 课程标准数据（k12_curriculum.json）

当前覆盖：小学 1-6 年级 + 初中 7-9 年级 + 高中 1 年级（高一），语文/数学/英语三科。

注意：`k12_curriculum.json` 由 `document.service.com.gagneflow.K12CurriculumLoader` 在启动时加载到内存，**不通过 Makefile 上传 Milvus**。Agent 通过 `queryK12Curriculum` 工具方法查询。

### 数据结构

```
学段 (小学/初中/高中)
  └── 学科 (语文/数学/英语/...)
        └── 年级 (一年级/二年级/...)
              └── 章节
                    └── 知识点[]
```

### 如何扩展

1. **补全年级**：在对应学科的 `"年级"` 数组中新增对象，如高二、高三
2. **补全学科**：在 `"学科"` 数组中新增对象，如 `{"name": "物理", "年级": [...]}`
3. **补全知识点**：在章节的 `"知识点"` 数组中追加字符串
4. **增加学段**：在 `"学段"` 数组中新增（如"学前教育"）

数据来源参考：《义务教育课程方案和课程标准（2022 年版）》《普通高中课程标准（2017 年版 2020 年修订）》
