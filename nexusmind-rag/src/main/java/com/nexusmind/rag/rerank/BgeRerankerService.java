package com.nexusmind.rag.rerank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.nexusmind.api.rag.dto.FusedCandidate;
import com.nexusmind.rag.rerank.tokenizer.BertPairTokenizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地 ONNX Cross-Encoder 精排服务（切片 4 慢路径）。
 *
 * <p>在 Java 进程内加载 BGE-Reranker INT8 量化模型，对 RRF Top-N 候选做 (Query, Doc) 句对
 * 打分重排，摆脱对外部云端 API 的依赖。核心防护（评审 P0/P1 落实）：
 * <ul>
 *   <li><b>常驻进程外内存治理</b>：{@link OrtEnvironment}/{@link OrtSession} 注册为 Spring 单例，
 *       {@code intraOpNumThreads=2} 避免与业务线程争抢 CPU；OnnxTensor/Result 在 finally 中显式 close，
 *       防止 Direct/Native 内存穿透（Java 堆无法 GC native 内存）。</li>
 *   <li><b>弹性降级</b>：{@code enabled=false} / 模型或词表文件缺失 / 初始化异常 / 推理异常 →
 *       一律回落纯 RRF 候选顺序，<b>系统永不阻断</b>。</li>
 *   <li><b>漏斗收敛</b>：仅对 RRF 粗排 Top-8 做 Cross-Encoder，单次推理 40~80ms；
 *       不对 Top-30 全量跑（CPU 单线程可达 300ms~1s）。</li>
 * </ul>
 *
 * <p><b>字段语义复用说明</b>：重排后写入 {@link FusedCandidate#rrfScore()} 的是 Cross-Encoder
 * relevance 原始 logit（越高越相关，等价于 BGE 官方 FlagReranker 排序依据），不再是 RRF 分数。
 * 后续若要区分两阶段分数，可给 FusedCandidate 增加独立 rerankScore 字段。
 */
@Service
public class BgeRerankerService {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankerService.class);
    private static final String OUTPUT_NAME = "logits";

    @Value("${nexusmind.rerank.enabled:true}")
    private boolean enabled;

    @Value("${nexusmind.rerank.model-path:./models/bge-reranker-large-quantized.onnx}")
    private String modelPath;

    @Value("${nexusmind.rerank.vocab-path:./models/vocab.txt}")
    private String vocabPath;

    @Value("${nexusmind.rerank.max-seq-len:256}")
    private int maxSeqLen;

    private OrtEnvironment env;
    private OrtSession session;
    private BertPairTokenizer tokenizer;
    private volatile boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[BgeReranker] 总开关关闭，跳过模型加载，直接走纯 RRF 快路径");
            return;
        }
        File modelFile = new File(modelPath);
        File vocabFile = new File(vocabPath);
        if (!modelFile.exists() || !vocabFile.exists()) {
            log.warn("[BgeReranker] 模型或词表文件不存在 (model={}, vocab={})，精排模块自动降级",
                    modelFile.getAbsolutePath(), vocabFile.getAbsolutePath());
            return;
        }
        try {
            this.env = OrtEnvironment.getEnvironment("NexusMind-Reranker");
            // 物理调优：绑定 2 核心计算，避免 CPU 打满；BASIC 优化级兼顾加载速度与推理性能
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                this.session = env.createSession(modelFile.getAbsolutePath(), options);
            }
            try (InputStream vocabStream = new FileInputStream(vocabFile)) {
                this.tokenizer = new BertPairTokenizer(vocabStream);
            }
            this.initialized = true;
            log.info("[BgeReranker] 本地 ONNX Cross-Encoder 加载成功，模型大小: {} MB",
                    modelFile.length() / (1024 * 1024));
        } catch (Exception e) {
            log.error("[BgeReranker] 初始化失败，自动降级为纯 RRF 路径", e);
            this.initialized = false;
        }
    }

    /** 是否可用：总开关 + 模型加载成功。调用方据此决定是否走慢路径。 */
    public boolean isAvailable() {
        return enabled && initialized;
    }

    /**
     * 对候选集执行 Cross-Encoder 精排打分。
     *
     * @param query      查询语句
     * @param candidates RRF 粗排候选（建议已收窄到 Top-8）
     * @param topN       精排后截断数量
     * @return 按精排分降序的候选；不可用时回落原顺序截断，绝不抛异常阻断主链路
     */
    public List<FusedCandidate> rerank(String query, List<FusedCandidate> candidates, int topN) {
        if (!isAvailable() || candidates == null || candidates.isEmpty()) {
            return safeSubList(candidates, topN);
        }
        int batchSize = candidates.size();
        List<String> queries = Collections.nCopies(batchSize, query);
        List<String> docs = candidates.stream().map(FusedCandidate::content).toList();

        BertPairTokenizer.TokenizedBatch batch = tokenizer.encodePairs(queries, docs, maxSeqLen);
        long[] shape = new long[]{batchSize, maxSeqLen};

        OnnxTensor inputIdsTensor = null;
        OnnxTensor attentionMaskTensor = null;
        OnnxTensor tokenTypeIdsTensor = null;
        OrtSession.Result result = null;
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(flatten(batch.inputIds())), shape);
            attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(flatten(batch.attentionMask())), shape);
            tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(flatten(batch.tokenTypeIds())), shape);

            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor);

            result = session.run(inputs);

            // 优先按输出名取 logits；缺失则按索引取首个输出（比硬编码 get(0) 更稳）
            OnnxValue out;
            Optional<OnnxValue> named = result.get(OUTPUT_NAME);
            if (named.isPresent()) {
                out = named.get();
            } else {
                out = result.get(0);
            }
            float[][] scores = (float[][]) out.getValue();

            // 组装并按 Cross-Encoder 原始 logit 降序（BGE 官方即按 raw logit 排序）
            List<ScoredPair> scored = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                float rawLogit = scores[i][0];
                scored.add(new ScoredPair(candidates.get(i), rawLogit));
            }
            scored.sort(Comparator.comparingDouble(ScoredPair::score).reversed());

            int limit = Math.min(topN, scored.size());
            List<FusedCandidate> reranked = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                ScoredPair p = scored.get(i);
                FusedCandidate o = p.candidate;
                // 复用原候选结构，rrfScore 字段改为承载 Cross-Encoder 精排分（float 窄化）
                reranked.add(new FusedCandidate(
                        o.docId(), o.collection(), o.content(),
                        (float) p.score(), o.denseRank(), o.sparseRank(), o.metadata()));
            }
            return reranked;
        } catch (Exception e) {
            log.error("[BgeReranker] 推理异常，平滑降级为原 RRF 候选顺序", e);
            return safeSubList(candidates, topN);
        } finally {
            // 显式释放 Native Off-Heap 内存，防止泄漏
            if (inputIdsTensor != null) {
                inputIdsTensor.close();
            }
            if (attentionMaskTensor != null) {
                attentionMaskTensor.close();
            }
            if (tokenTypeIdsTensor != null) {
                tokenTypeIdsTensor.close();
            }
            if (result != null) {
                result.close();
            }
        }
    }

    private List<FusedCandidate> safeSubList(List<FusedCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.min(topN, candidates.size());
        return new ArrayList<>(candidates.subList(0, limit));
    }

    private long[] flatten(long[][] array) {
        int rows = array.length;
        int cols = array[0].length;
        long[] flat = new long[rows * cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(array[r], 0, flat, r * cols, cols);
        }
        return flat;
    }

    private record ScoredPair(FusedCandidate candidate, double score) {
    }

    @PreDestroy
    public void close() {
        try {
            if (session != null) {
                session.close();
            }
            if (env != null) {
                env.close();
            }
        } catch (Exception ignored) {
            // 关闭期异常不阻断容器销毁
        }
    }
}
