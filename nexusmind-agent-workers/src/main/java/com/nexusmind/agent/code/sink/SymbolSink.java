package com.nexusmind.agent.code.sink;

import com.nexusmind.api.dto.UnifiedCodeSymbol;

import java.util.List;

/**
 * 符号落库通道（CodeAgent 切片1 硬伤C 修复点）。
 *
 * <p>将每文件提取出的符号增量持久化，供 W7 的 Milvus + BM25 + RRF 检索直接消费。
 * 实现可选择落 JSONL（本地沙箱，默认 {@link FileSymbolSink}）或直写 PG（后续按需替换）。
 */
public interface SymbolSink {

    /**
     * 增量持久化一批符号。
     *
     * @param taskId  任务标识（用于落盘分区 / 追踪）
     * @param symbols 当前文件提取出的符号集合
     */
    void persist(String taskId, List<UnifiedCodeSymbol> symbols);
}
