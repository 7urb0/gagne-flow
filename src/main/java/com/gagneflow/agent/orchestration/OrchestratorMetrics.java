package com.gagneflow.agent.orchestration;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 编排器累计指标(变量: 经 Micrometer 暴露 gagneflow.chat.agent.* + 内存级近实时视图)。
 * 供转正指标灰度评估:
 *  - 调用次数 / token 估算累计 / 端到端耗时累计
 *  - 完整编排路径(PLAN..FINISH)次数 与 快捷路径(EXECUTE)次数
 *  - 复核拦截次数(REVIEW 判定 FAIL 且发生回溯)
 * 转正判定参考 application.yml gagneflow.agent 段注释: token 净节省 >=10%、
 * 延迟增幅 <=20%、复核检出率 >0, 灰度窗口 4 周。
 */
public final class OrchestratorMetrics {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorMetrics.class);

    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicLong totalTokenEstimate = new AtomicLong();
    private final AtomicLong totalElapsedMs = new AtomicLong();
    private final AtomicInteger fullPathCount = new AtomicInteger();
    private final AtomicInteger shortcutPathCount = new AtomicInteger();
    private final AtomicInteger reviewInterceptCount = new AtomicInteger();

    /** 2026-08-27: 灰度统计接入 Micrometer(非 Spring 环境为 null, 纯内存视图不受影响) */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public void record(OrchestratorResult r) {
        this.invocationCount.incrementAndGet();
        this.totalTokenEstimate.addAndGet(r.tokenEstimate());
        this.totalElapsedMs.addAndGet(r.elapsedMs());
        boolean full = r.path().contains("PLAN");
        if (full) {
            this.fullPathCount.incrementAndGet();
        } else {
            this.shortcutPathCount.incrementAndGet();
        }
        if (r.path().contains("FIX")) {
            this.reviewInterceptCount.incrementAndGet();
        }
        // Micrometer 指标(gagneflow.chat.agent.*): 供 /actuator/metrics 与 Prometheus 灰度汇总
        MeterRegistry reg = this.meterRegistry;
        if (reg != null) {
            reg.counter("gagneflow.chat.agent.invocations").increment();
            reg.counter("gagneflow.chat.agent.token.estimate").increment(r.tokenEstimate());
            reg.timer("gagneflow.chat.agent.elapsed")
                    .record(java.time.Duration.ofMillis(r.elapsedMs()));
            reg.counter("gagneflow.chat.agent.path", "type", full ? "full" : "shortcut").increment();
            if (r.path().contains("FIX")) {
                reg.counter("gagneflow.chat.agent.review.intercepts").increment();
            }
        }
        log.debug("[ORCH] record path={} full={} token={} elapsed={}ms",
                r.path(), full, r.tokenEstimate(), r.elapsedMs());
    }

    public int invocationCount() {
        return this.invocationCount.get();
    }

    public long totalTokenEstimate() {
        return this.totalTokenEstimate.get();
    }

    public long totalElapsedMs() {
        return this.totalElapsedMs.get();
    }

    public int fullPathCount() {
        return this.fullPathCount.get();
    }

    public int shortcutPathCount() {
        return this.shortcutPathCount.get();
    }

    public int reviewInterceptCount() {
        return this.reviewInterceptCount.get();
    }
}