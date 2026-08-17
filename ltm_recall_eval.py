# -*- coding: utf-8 -*-
"""
GagneFlow 长期事实记忆(LTM) 事实召回率对比评测: LTM完整方案 vs 纯关键词匹配

用法: python ltm_recall_eval.py
环境: DASHSCOPE_API_KEY + Milvus(127.0.0.1:19530) + jieba
场景: 模拟对话记忆——用户曾陈述的事实(fact) 存入 LTM, 新对话提问(query) 时召回 top3 注入
      查询措辞与事实字面不重叠(指代/同义/概括), 模拟真实"回忆"场景
基线:
  1. LTM 完整方案: text-embedding-v4 余弦相似度 x 来源阶段权重(对齐 LongTermMemoryService.vectorSearch)
  2. 纯关键词匹配(代码实现): query.split("\\s+") 任一子串被 fact 包含 (对齐 keywordSearch/containsKeyword)
  3. 纯关键词匹配(jieba): 查询 jieba 分词后任一词被 fact 包含
指标: Recall@3(与 Top-3 注入一致) + MRR
"""
import os
import math
import jieba
from mrr_eval import embed

# 来源阶段权重（对齐 LongTermMemoryService.sourcePhaseWeight）
SOURCE_WEIGHT = {"FINAL_DECISION": 1.0, "USER_EXPLICIT": 1.0, "SUMMARY_EXTRACTED": 0.85}

# (fact, query, source) —— query 与 fact 字面刻意不重叠
FACTS = [
    ("用户教小学三年级数学", "我带的班是哪个年级的？", "USER_EXPLICIT"),
    ("学生计算能力弱需要加强口算", "上次说的学习短板在哪？", "SUMMARY_EXTRACTED"),
    ("用户偏好启发式提问引导", "平时喜欢怎么讲课？", "USER_EXPLICIT"),
    ("一节课安排40分钟讲练结合", "一节课的时间怎么分配？", "FINAL_DECISION"),
    ("用户希望教案加入游戏化环节", "课堂设计上有什么要求？", "USER_EXPLICIT"),
    ("学生注意力集中时间约15分钟", "孩子们能专注多久？", "SUMMARY_EXTRACTED"),
    ("用户计划下月讲分数单元", "接下来准备教什么内容？", "FINAL_DECISION"),
    ("批改作业发现进位错误率高", "作业里反映的问题是什么？", "SUMMARY_EXTRACTED"),
    ("用户喜欢用案例导入新课", "每节课开头怎么安排？", "USER_EXPLICIT"),
    ("班级人数45人", "班里有多少学生？", "USER_EXPLICIT"),
    ("用户期望作业量控制在20分钟内", "作业布置有什么讲究？", "FINAL_DECISION"),
    ("学生应用题审题不清", "解题方面有什么困难？", "SUMMARY_EXTRACTED"),
    ("用户反对死记硬背强调理解", "对记忆背诵怎么看？", "USER_EXPLICIT"),
    ("下周有公开课需要准备", "最近有什么重要安排？", "FINAL_DECISION"),
    ("用户常用多媒体课件辅助教学", "上课喜欢用什么工具？", "USER_EXPLICIT"),
    ("学生间基础差异大需要分层教学", "班级学情有什么特点？", "SUMMARY_EXTRACTED"),
    ("用户打算期中后开家长会", "学期中有哪些计划？", "FINAL_DECISION"),
    ("课件每页文字不超过50字", "做幻灯片有什么习惯？", "USER_EXPLICIT"),
    ("学生课堂发言不积极", "课堂气氛怎么样？", "SUMMARY_EXTRACTED"),
    ("用户认可小老师互助模式", "有没有偏爱的教学方法？", "FINAL_DECISION"),
    ("用户希望每单元配一次小测验", "单元学习怎么验收？", "USER_EXPLICIT"),
    ("学生中午容易犯困", "什么时候孩子们状态差？", "SUMMARY_EXTRACTED"),
    ("用户周六上午参加教研", "什么时间方便讨论？", "FINAL_DECISION"),
    ("用户倾向先复习再上新课", "新课之前习惯做什么？", "USER_EXPLICIT"),
]


def cosine(a, b):
    if len(a) != len(b):
        return -1.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def ltm_retrieve_top3(query_vec, fact_vecs, sources):
    """LTM 完整方案: cos x 来源权重 排序取 top3"""
    scores = [cosine(query_vec, fv) * SOURCE_WEIGHT.get(src, 1.0)
              for fv, src in zip(fact_vecs, sources)]
    order = sorted(range(len(scores)), key=lambda i: -scores[i])
    return order[:3]


def kw_code_top3(query, facts):
    """纯关键词匹配(代码实现): query.split 任一子串被 fact 包含"""
    kws = query.split()
    if not kws:
        return []
    hits = [i for i, f in enumerate(facts) if any(k in f for k in kws)]
    return hits[:3]


def kw_jieba_top3(query, facts):
    """纯关键词匹配(jieba): 查询分词后任一词被 fact 包含"""
    kws = [t for t in jieba.lcut(query) if t.strip()]
    if not kws:
        return []
    hits = [i for i, f in enumerate(facts) if any(k in f for k in kws)]
    return hits[:3]


def evaluate(retrieve_fn, facts, queries, fact_vecs, sources, query_vecs):
    hit3 = 0
    mrrs = []
    for i in range(len(facts)):
        top3 = retrieve_fn(i)
        if i in top3:
            hit3 += 1
            rank = top3.index(i) + 1
            mrrs.append(1.0 / rank)
        else:
            mrrs.append(0.0)
    n = len(facts)
    return {
        "Recall@3": round(hit3 / n, 4),
        "MRR": round(sum(mrrs) / n, 4),
    }


def main():
    facts = [f for f, _, _ in FACTS]
    queries = [q for _, q, _ in FACTS]
    sources = [s for _, _, s in FACTS]
    n = len(facts)
    print("facts:", n)

    print("embedding facts...")
    fact_vecs = [embed(f) for f in facts]
    print("embedding queries...")
    query_vecs = [embed(q) for q in queries]

    print("\n=== LTM 完整方案（embedding x 来源权重, top3）===")
    ltm = evaluate(
        lambda i: ltm_retrieve_top3(query_vecs[i], fact_vecs, sources),
        facts, queries, fact_vecs, sources, query_vecs)
    for k, v in ltm.items():
        print("%s: %s" % (k, v))

    print("\n=== 纯关键词匹配（代码实现: 整句子串, top3）===")
    kw_code = evaluate(
        lambda i: kw_code_top3(queries[i], facts),
        facts, queries, fact_vecs, sources, query_vecs)
    for k, v in kw_code.items():
        print("%s: %s" % (k, v))

    print("\n=== 纯关键词匹配（jieba 分词, top3）===")
    kw_jieba = evaluate(
        lambda i: kw_jieba_top3(queries[i], facts),
        facts, queries, fact_vecs, sources, query_vecs)
    for k, v in kw_jieba.items():
        print("%s: %s" % (k, v))

    print("\n=== 提升（LTM vs 关键词）===")
    for k in ["Recall@3", "MRR"]:
        base = kw_code[k]
        d = ltm[k] - base
        print("%s: 关键词 %.4f -> LTM %.4f (%+.1fpp / %+.0f%%)"
              % (k, base, ltm[k], d * 100, (d / base * 100 if base else 0)))


if __name__ == "__main__":
    main()
