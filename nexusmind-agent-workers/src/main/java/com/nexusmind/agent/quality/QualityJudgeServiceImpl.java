package com.nexusmind.agent.quality;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nexusmind.agent.llm.LLMClient;
import com.nexusmind.api.quality.IQualityJudgeService;
import com.nexusmind.api.quality.JudgeRequest;
import com.nexusmind.api.quality.JudgeResult;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内联质量评测实现（切片 4）。
 *
 * <p>复用 {@link LLMClient#chatOrFallback} 跑 judge prompt 并解析结构化评分；
 * 评分在 agent-workers 进程内执行，与 LLMClient 共用 base-url/api-key/降级策略。
 *
 * <p><b>best-effort 铁律</b>：LLM 返回非 JSON / 字段缺失 / 越界 → 回退 {@code score=0.0} 并记 warn，
 * 绝不让评测异常向上抛、绝不拖垮调用方主链路（对齐切片 3 监控降级范式）。
 */
@DubboService(interfaceClass = IQualityJudgeService.class, timeout = 30000)
public class QualityJudgeServiceImpl implements IQualityJudgeService {

    private static final Logger log = LoggerFactory.getLogger(QualityJudgeServiceImpl.class);
    private static final Gson gson = new Gson();

    private final LLMClient llmClient;

    /** 构造函数注入，便于单测以 mock LLMClient 注入（Spring 自动装配同类型 @Component）。 */
    public QualityJudgeServiceImpl(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public JudgeResult judge(JudgeRequest req) {
        if (req == null || req.contentToJudge() == null || req.contentToJudge().isBlank()) {
            return new JudgeResult(0.0, Collections.emptyMap(), "待评内容为空，降级为 0 分", "n/a");
        }
        String system = QualityJudgePrompt.systemPrompt(req.taskType());
        String user = QualityJudgePrompt.userPrompt(req.contentToJudge());
        try {
            String raw = llmClient.chatOrFallback(system, user, 0.2, 1200, QualityJudgePrompt::fallbackJudgeJson);
            return parse(raw, req.taskType());
        } catch (Exception e) {
            log.warn("[QualityJudge] 评测失败(非阻断): taskType={}, err={}", req.taskType(), e.getMessage());
            return new JudgeResult(0.0, Collections.emptyMap(), "评测异常，降级为 0 分", "n/a");
        }
    }

    /**
     * 解析 LLM 返回的 JSON，缺失/越界字段回退 0.0，绝不因脏数据抛异常。
     */
    private JudgeResult parse(String raw, String taskType) {
        try {
            String json = stripFences(raw);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj == null) {
                return degraded();
            }
            double score = (obj.has("score") && !obj.get("score").isJsonNull())
                    ? clamp(obj.get("score").getAsDouble()) : 0.0;

            Map<String, Double> dims = new LinkedHashMap<>();
            if (obj.has("dimensions") && obj.get("dimensions").isJsonObject()) {
                for (var e : obj.getAsJsonObject("dimensions").entrySet()) {
                    try {
                        dims.put(e.getKey(), clamp(e.getValue().getAsDouble()));
                    } catch (Exception ignore) {
                        // 跳过非数值维度，不阻断整体评分
                    }
                }
            }
            String rationale = obj.has("rationale") ? obj.get("rationale").getAsString() : "";
            return new JudgeResult(score, dims, rationale, "judge:" + (taskType == null ? "general" : taskType));
        } catch (Exception e) {
            log.warn("[QualityJudge] JSON 解析失败(非阻断)，降级 0 分: {}", e.getMessage());
            return degraded();
        }
    }

    private static JudgeResult degraded() {
        return new JudgeResult(0.0, Collections.emptyMap(), "评分解析失败，降级为 0 分", "n/a");
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    /** 剥离 LLM 偶发的 ```json ... ``` 代码围栏，提升解析鲁棒性。 */
    private static String stripFences(String raw) {
        if (raw == null) {
            return "{}";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
        }
        return s.trim();
    }
}
