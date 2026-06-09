package com.dragon.agent.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RAG 调试服务——委托给 {@link RagPipelineService} 获取数据，
 * 仅负责数据结构转换和分步回调适配。
 */
@Service
public class RagDebugService {

    private static final Logger log = LoggerFactory.getLogger(RagDebugService.class);

    @Autowired private RagPipelineService pipelineService;

    public record DebugResult(String query, long totalMs, List<PipelineData> steps,
            List<Map<String, Object>> finalTraces, String finalContext, int finalCount) {}
    public record PipelineData(int step, String name, String icon, long durationMs,
            String status, String summary, Map<String, Object> detail) {}

    /** 同步一次性调试：走统一管线 + 收集步骤 */
    public DebugResult debug(String query, Long userId) {
        try {
            long start = System.currentTimeMillis();
            List<PipelineData> steps = new ArrayList<>();
            var result = pipelineService.executeWithSteps(query, userId, s -> {
                steps.add(new PipelineData(s.step(), s.name(), s.icon(), s.durationMs(), s.status(), s.summary(), s.detail()));
            });
            return new DebugResult(query, System.currentTimeMillis() - start, steps, result.traces(), result.context(), result.traces().size());
        } catch (Exception e) {
            log.error("RAG debug failed", e);
            return new DebugResult(query, 0, List.of(), List.of(), "", 0);
        }
    }

    /** 分步回调（轮询用）——走统一管线 + 每步实时回调 */
    public void debugStepByStep(String query, Long userId,
            java.util.function.Consumer<PipelineData> cb,
            java.util.function.Consumer<DebugResult> done) {
        try {
            long start = System.currentTimeMillis();
            var result = pipelineService.executeWithSteps(query, userId, s -> {
                cb.accept(new PipelineData(s.step(), s.name(), s.icon(), s.durationMs(), s.status(), s.summary(), s.detail()));
            });
            done.accept(new DebugResult(query, System.currentTimeMillis() - start, List.of(), result.traces(), result.context(), result.traces().size()));
        } catch (Exception e) {
            log.error("debugStepByStep failed", e);
        }
    }
}
