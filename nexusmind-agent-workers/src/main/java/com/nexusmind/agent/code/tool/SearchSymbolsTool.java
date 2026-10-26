package com.nexusmind.agent.code.tool;

import com.nexusmind.agent.code.index.SymbolIndex;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.rbac.AgentSecured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 原子工具：符号与调用图拓扑检索（CodeAgent 切片3）。
 *
 * <p><b>数据源（修复原设计的"空注册表"硬伤）：</b>检索不再是进程内无人填充的
 * {@code CopyOnWriteArrayList}，而是消费切片2 的落库产物
 * {@code projects/<taskId>/symbols.jsonl}，经 {@link SymbolIndex#loadFromJsonl} 装载为倒排索引；
 * 同时 {@link ParseAstTool} 解析时也会实时增量写入同一索引，两条入口最终一致。
 *
 * <p><b>复杂度：</b>反查调用者走倒排表 O(1) 命中，取代原设计"全表双重循环"的 O(N²)；
 * 关键词检索仍是 O(N) 过滤（关键词本就是模糊语义查询，无法索引化），但结果上限
 * {@link SymbolIndex#MAX_QUERY_RESULTS} 兜底，避免撑爆 LLM 上下文。
 *
 * <p><b>只读：</b>纯查询，不修改任何符号/文件。
 */
@Component
public class SearchSymbolsTool {

    private static final Logger log = LoggerFactory.getLogger(SearchSymbolsTool.class);

    private final SymbolIndex symbolIndex;

    public SearchSymbolsTool(SymbolIndex symbolIndex) {
        this.symbolIndex = symbolIndex;
    }

    /**
     * @param taskId      任务作用域（索引分区）；为空则跨全部分区检索
     * @param query       关键词（匹配符号全限定 ID / 简名）；为空则仅按 kind 过滤
     * @param kind        CLASS / INTERFACE / ENUM / RECORD / METHOD / CONSTRUCTOR / FIELD；为空不过滤
     * @param findCallees 是否展开该符号的下游调用
     * @param findCallers 是否反查该符号的上游调用者
     */
    public record SearchRequest(String taskId,
                                String query,
                                String kind,
                                boolean findCallees,
                                boolean findCallers) {
    }

    /**
     * @param matchedSymbol    命中的符号
     * @param downstreamCallees 下游调用（符号自带 callees，全限定签名）
     * @param upstreamCallers   上游调用者 symbolId 列表（倒排索引反查）
     */
    public record SearchResult(UnifiedCodeSymbol matchedSymbol,
                               List<String> downstreamCallees,
                               List<String> upstreamCallers) {
    }

    /**
     * @param taskId     任务标识
     * @param loaded     从 JSONL 成功加载的符号数
     * @param indexSize  加载后该分区的符号总数
     */
    public record LoadIndexResponse(String taskId, int loaded, int indexSize) {
    }

    /**
     * 符号与调用图拓扑检索。
     */
    @AgentSecured(allowedAgents = {"codeAgent"}, readOnly = true, audit = true)
    public List<SearchResult> execute(SearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SearchRequest 不能为空");
        }

        List<UnifiedCodeSymbol> matched = symbolIndex.find(
                request.taskId(), request.query(), request.kind());

        List<SearchResult> results = new ArrayList<>(matched.size());
        for (UnifiedCodeSymbol symbol : matched) {
            List<String> callees = request.findCallees() && symbol.callees() != null
                    ? dedupeSorted(symbol.callees())
                    : List.of();
            List<String> callers = request.findCallers()
                    ? symbolIndex.callersOf(request.taskId(), symbol)
                    : List.of();
            results.add(new SearchResult(symbol, callees, callers));
        }

        log.debug("[SearchSymbolsTool] query={}, kind={}, hits={}, callers={}",
                request.query(), request.kind(), results.size(), request.findCallers());
        return results;
    }

    /**
     * 从切片2 的分析产物装载索引（幂等：先清空该分区再加载）。
     *
     * @param taskId 任务标识
     */
    @AgentSecured(allowedAgents = {"codeAgent"}, readOnly = true, audit = true)
    public LoadIndexResponse loadTaskIndex(String taskId) throws IOException {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        symbolIndex.clear(taskId);
        int loaded = symbolIndex.loadFromJsonl(taskId);
        int indexSize = symbolIndex.size(taskId);
        log.info("[SearchSymbolsTool] 任务索引装载完成: taskId={}, loaded={}, indexSize={}",
                taskId, loaded, indexSize);
        return new LoadIndexResponse(taskId, loaded, indexSize);
    }

    private static List<String> dedupeSorted(List<String> values) {
        Set<String> unique = new LinkedHashSet<>(values);
        return unique.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
