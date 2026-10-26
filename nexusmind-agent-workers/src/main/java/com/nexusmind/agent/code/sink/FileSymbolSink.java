package com.nexusmind.agent.code.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.storage.AppendOnlyFileChannelWriter;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 符号落库通道默认实现：增量追加到沙箱 JSONL。
 *
 * <p>复用既有 {@link AppendOnlyFileChannelWriter}（Append-only，禁止截断），
 * 每符号一行 JSON，落点 {@code projects/<taskId>/symbols.jsonl}。
 * W7 的 RAG 引擎可直接按行读取该 JSONL 做向量化与检索，无需引入 PG 依赖。
 *
 * <p>落库失败仅告警跳过，<b>绝不</b>阻断主解析管线（与单文件容灾同源设计）。
 */
@Component
public class FileSymbolSink implements SymbolSink {

    private static final Logger log = LoggerFactory.getLogger(FileSymbolSink.class);

    private final ObjectMapper objectMapper;
    private final PathSandboxValidator validator;

    public FileSymbolSink(ObjectMapper objectMapper, PathSandboxValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Override
    public void persist(String taskId, List<UnifiedCodeSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        try {
            Path target = validator.resolveSecurePath("projects/" + taskId + "/symbols.jsonl");
            StringBuilder sb = new StringBuilder();
            for (UnifiedCodeSymbol s : symbols) {
                sb.append(objectMapper.writeValueAsString(s)).append("\n");
            }
            AppendOnlyFileChannelWriter.appendToFile(target, sb.toString());
        } catch (Exception e) {
            log.warn("[CodeAgent] 符号落库失败(降级跳过，不阻断主流程): taskId={}, error={}", taskId, e.getMessage());
        }
    }
}
