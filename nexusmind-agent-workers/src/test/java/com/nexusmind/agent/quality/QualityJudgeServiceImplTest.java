package com.nexusmind.agent.quality;

import com.nexusmind.agent.llm.LLMClient;
import com.nexusmind.api.quality.JudgeRequest;
import com.nexusmind.api.quality.JudgeResult;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 4 验收：内联 LLM-as-Judge 评分解析与降级。
 *
 * <p>不依赖真实 LLM / Dubbo 运行时——以匿名子类 mock {@link LLMClient#chatOrFallback}
 * 返回指定 canned JSON，验证：合法 JSON 解析、越界 clamp、脏数据降级 0 分、空内容降级、
 * 代码围栏剥离。
 */
class QualityJudgeServiceImplTest {

    /** 匿名子类 mock LLMClient：直接返回指定字符串，规避真实 HTTP / Spring 注入。 */
    private LLMClient mockLlm(final String canned) {
        return new LLMClient() {
            @Override
            public String chatOrFallback(String systemPrompt, String userPrompt, double temperature,
                                         int maxTokens, Supplier<String> mockSupplier) {
                return canned;
            }
        };
    }

    @Test
    void judge_parsesValidJson() {
        QualityJudgeServiceImpl svc = new QualityJudgeServiceImpl(mockLlm(
                "{\"score\":0.85,\"dimensions\":{\"coherence\":0.9,\"relevance\":0.8},\"rationale\":\"结构清晰\"}"));
        JudgeResult r = svc.judge(new JudgeRequest("journal", "突破点明确，阻碍归因到位", null, null));

        assertEquals(0.85, r.score(), 1e-9, "score 应正确解析");
        assertEquals(2, r.dimensions().size(), "应解析出 2 个维度分");
        assertEquals(0.9, r.dimensions().get("coherence"), 1e-9);
        assertTrue(r.rationale().contains("结构清晰"), "应保留评分理由");
    }

    @Test
    void judge_clampsOutOfRangeScore() {
        QualityJudgeServiceImpl svc = new QualityJudgeServiceImpl(mockLlm(
                "{\"score\":1.7,\"dimensions\":{},\"rationale\":\"x\"}"));
        JudgeResult r = svc.judge(new JudgeRequest("code", "some code analysis", null, null));
        assertEquals(1.0, r.score(), 1e-9, "越界分数应被 clamp 到 1.0");
    }

    @Test
    void judge_dirtyJson_degradesToZero() {
        QualityJudgeServiceImpl svc = new QualityJudgeServiceImpl(mockLlm("这根本不是 json @@@"));
        JudgeResult r = svc.judge(new JudgeRequest("web_kb", "content", null, null));
        assertEquals(0.0, r.score(), 1e-9, "脏数据应降级为 0 分");
        assertNotNull(r.dimensions());
        assertTrue(r.dimensions().isEmpty(), "脏数据维度应为空");
    }

    @Test
    void judge_emptyContent_degradesToZero() {
        // 即便 LLM 返回高分，空内容也应直接降级为 0 分（不浪费一次 LLM 调用路径的语义）
        QualityJudgeServiceImpl svc = new QualityJudgeServiceImpl(mockLlm("{\"score\":0.9}"));
        JudgeResult r = svc.judge(new JudgeRequest("journal", "   ", null, null));
        assertEquals(0.0, r.score(), 1e-9, "空内容应降级为 0 分");
    }

    @Test
    void judge_handlesFencedJson() {
        QualityJudgeServiceImpl svc = new QualityJudgeServiceImpl(mockLlm(
                "```json\n{\"score\":0.6,\"dimensions\":{\"relevance\":0.6},\"rationale\":\"ok\"}\n```"));
        JudgeResult r = svc.judge(new JudgeRequest("tutor", "why virtual threads", null, null));
        assertEquals(0.6, r.score(), 1e-9, "应剥离 ```json 围栏后正确解析");
    }
}
