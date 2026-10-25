package com.nexusmind.agent.tutor.diagnostic;

import com.nexusmind.agent.tutor.diagnostic.model.ConfusionAnalysisResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混淆概念轻量级内存分析器 (TutorAgent 只读诊断链路)
 *
 * 在向 LLM 发起推理前，对日记/阻碍文本做本地微秒级扫描，识别经典易混淆
 * 架构概念对 (如 Virtual Threads vs WebFlux、Triple vs gRPC)，把命中结果
 * 注入苏格拉底 Prompt，使生成的辨析题直击薄弱点。
 *
 * 执行边界: 纯内存计算，无文件 I/O、无网络、无 LLM 调用，符合 READ_ONLY_RAG。
 *
 * 匹配规则 (切片3 评审修复版):
 * 1. 触发条件为双侧同时命中 (hitsA > 0 && hitsB > 0)——辨析题需要对比双方
 *    都在场才有意义；单侧高频不触发 (历史版本注释曾声称支持单侧触发但未实现，
 *    评审后以双侧规则为准并删除误导性注释)。
 * 2. 英文关键词使用 \b 词边界匹配，防御子串误报 (mono -> monolithic、
 *    flux -> influx、wal -> wallet)；中文关键词无词边界概念，使用原样包含。
 * 3. hitFrequency 统计真实出现次数 (同一关键词多次出现累计)，而非关键词种类数。
 */
@Service
public class WeaknessDiagnosticService {

    /** 无命中时的统一默认域名 (与 SocraticPromptTemplate.DEFAULT_DOMAIN 一致) */
    public static final String DEFAULT_DOMAIN = "General Architecture";

    /**
     * 预置混淆概念对
     *
     * 选材原则: 仅收录开发者真实高频混淆、且能用"核心差异一句话"讲清边界的技术对。
     * 扩展方式: 直接追加 PredefinedConfusionPair 条目，无需改匹配逻辑。
     */
    private record PredefinedConfusionPair(
            String conceptA,
            String conceptB,
            String domain,
            String summary,
            List<String> keywordsA,
            List<String> keywordsB
    ) {
    }

    private static final List<PredefinedConfusionPair> CONFUSION_KNOWLEDGE_BASE = List.of(
            new PredefinedConfusionPair(
                    "Virtual Thread",
                    "Reactive Streams (WebFlux)",
                    "Concurrency",
                    "阻塞式编程心智模型与虚拟线程解耦 vs 响应式事件循环与背压机制",
                    List.of("virtual thread", "虚拟线程", "fiber", "project loom"),
                    List.of("webflux", "reactor", "mono", "flux", "reactive", "响应式")),
            new PredefinedConfusionPair(
                    "Dubbo Triple",
                    "Standard gRPC",
                    "RPC/Network",
                    "Triple 在 HTTP/2 协议上对浏览器直连（Triple REST 模式）及多协议平滑降级的扩展 vs 原生 gRPC Protobuf 严格绑定",
                    List.of("triple", "dubbo3"),
                    List.of("grpc", "protobuf", "http/2")),
            new PredefinedConfusionPair(
                    "RRF Fusion",
                    "Cross-Encoder Rerank",
                    "RAG/Search",
                    "基于排名的无参轻量多路倒数召回融合 vs 基于 Transformer 交叉注意力的深度语义精排",
                    List.of("rrf", "reciprocal rank", "排名融合"),
                    List.of("cross-encoder", "reranker", "bge-reranker", "重排")),
            new PredefinedConfusionPair(
                    "Append-Only Log",
                    "In-Place Update",
                    "Storage",
                    "以顺序写吞吐和不可篡改性换取空间放大 vs 以就地修改换取紧凑存储但丧失无锁并发与审计能力",
                    List.of("append-only", "只增", "追加写", "wal"),
                    List.of("覆盖写", "truncate", "就地更新", "update")));

    /**
     * 扫描文本并计算混淆概念匹配度
     *
     * @param text 日记/阻碍文本 (可为空)
     * @return 诊断结果；空输入返回空匹配 + 默认域 + 0.0 置信度
     */
    public ConfusionAnalysisResult analyzeText(String text) {
        if (text == null || text.isBlank()) {
            return new ConfusionAnalysisResult(List.of(), DEFAULT_DOMAIN, 0.0);
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        List<ConfusionAnalysisResult.MatchedConfusionPair> matched = new ArrayList<>();
        Map<String, Integer> domainHitCounter = new HashMap<>();
        int totalHits = 0;

        for (var pair : CONFUSION_KNOWLEDGE_BASE) {
            int hitsA = countOccurrences(lowerText, pair.keywordsA());
            int hitsB = countOccurrences(lowerText, pair.keywordsB());

            // 双侧同时命中才构成有效辨析对
            if (hitsA > 0 && hitsB > 0) {
                int pairHits = hitsA + hitsB;
                totalHits += pairHits;
                matched.add(new ConfusionAnalysisResult.MatchedConfusionPair(
                        pair.conceptA(),
                        pair.conceptB(),
                        pair.domain(),
                        pair.summary(),
                        pairHits));
                domainHitCounter.merge(pair.domain(), pairHits, Integer::sum);
            }
        }

        String topDomain = domainHitCounter.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_DOMAIN);

        // 置信度: 0.4 基线 (至少命中一对) + 每 20 次命中 +0.05，封顶 1.0
        // 与命中强度挂钩而非仅匹配对数——命中 1 次与命中 10 次的置信度应有区分
        double confidence = matched.isEmpty() ? 0.0 : Math.min(1.0, 0.4 + totalHits * 0.05);

        return new ConfusionAnalysisResult(matched, topDomain, confidence);
    }

    /**
     * 统计一组关键词在文本中的总出现次数 (真实频次)
     *
     * 英文关键词使用 \b 词边界 (ASCII 词字符边界)，防御子串误报；
     * 含非 ASCII 字符的关键词 (中文) 无词边界概念，退化为原样包含匹配。
     */
    private int countOccurrences(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            Pattern pattern = isPureAscii(keyword)
                    ? Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(Pattern.quote(keyword));
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPureAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }
}
