package com.nexusmind.agent.code.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.security.sandbox.PathSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码符号倒排索引（CodeAgent 切片3：{@code SearchSymbolsTool} 的数据底座）。
 *
 * <p><b>为什么需要它：</b>切片2 已把符号表增量落成 {@code projects/<taskId>/symbols.jsonl}，
 * 但"文件"不能做拓扑查询。本索引把扁平 JSONL 反转为两张表：
 * <ul>
 *   <li>{@code byId}：{@code symbolId -> UnifiedCodeSymbol}，支撑 O(1) 精确取符号；</li>
 *   <li>{@code callersByKey}：{@code calleeKey -> Set<callerSymbolId>}，支撑 <b>O(1)</b> 反查调用者，
 *       取代原设计中"全表双重循环（O(N²)）"的 callers 计算。</li>
 * </ul>
 *
 * <p><b>关键正确性约束（修复原设计的假阳性）：</b>反向边的键一律是<b>精确键</b>
 * （全限定签名 / 方法签名 / 裸方法名），构建与查询两侧使用<b>同一套</b> {@link #calleeKeys}
 * 归一函数，<b>绝不</b>使用 {@code contains} 子串匹配。否则搜 {@code run} 会命中
 * {@code runner}、{@code Runnable} 等无关符号，污染调用图。
 *
 * <p><b>精确优先策略：</b>反查调用者时先用全限定签名键；命中即返回。仅当精确键无结果时，
 * 才退化为"方法签名 / 裸方法名"键（仍是等值匹配，非子串），并剔除自调用。
 *
 * <p><b>分区隔离：</b>索引按 {@code scope}（通常是 taskId）分区，多任务并发分析互不污染，
 * 查询时 {@code scope} 为空则跨全部分区聚合。
 */
@Component
public class SymbolIndex {

    private static final Logger log = LoggerFactory.getLogger(SymbolIndex.class);

    /** 单次查询结果上限，防止 LLM 工具调用被超大结果集撑爆上下文 */
    public static final int MAX_QUERY_RESULTS = 200;

    private final Map<String, Partition> partitions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final PathSandboxValidator validator;

    public SymbolIndex(ObjectMapper objectMapper, PathSandboxValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * 单个任务作用域的索引分区。
     */
    private static final class Partition {
        /** symbolId -> 符号 */
        final Map<String, UnifiedCodeSymbol> byId = new ConcurrentHashMap<>();
        /** calleeKey -> 调用者 symbolId 集合 */
        final Map<String, Set<String>> callersByKey = new ConcurrentHashMap<>();
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 批量注册符号并同步维护反向调用索引。
     *
     * @param scope   作用域（taskId）；为空时归入 {@code "default"} 分区
     * @param symbols 待注册符号
     * @return 注册后该分区的符号总数
     */
    public int registerAll(String scope, Collection<UnifiedCodeSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return size(scope);
        }
        Partition partition = partitionOf(scope);
        for (UnifiedCodeSymbol symbol : symbols) {
            if (symbol == null || symbol.symbolId() == null || symbol.symbolId().isBlank()) {
                continue;
            }
            partition.byId.put(symbol.symbolId(), symbol);

            Set<String> keys = new LinkedHashSet<>();
            if (symbol.callees() != null) {
                for (String callee : symbol.callees()) {
                    calleeKeys(callee, keys);
                }
            }
            for (String key : keys) {
                partition.callersByKey
                        .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                        .add(symbol.symbolId());
            }
        }
        return partition.byId.size();
    }

    /**
     * 清空指定作用域的索引（重新分析前调用，保证幂等）。
     *
     * @param scope 作用域；为空则清空全部
     */
    public void clear(String scope) {
        if (isBlank(scope)) {
            partitions.clear();
        } else {
            partitions.remove(scope);
        }
    }

    /**
     * 从切片2 的分析产物 {@code projects/<scope>/symbols.jsonl} 加载索引。
     *
     * <p>逐行流式读取，避免把全量 JSONL 一次性读进内存。
     *
     * @param scope 作用域（taskId）
     * @return 成功加载的符号数量（文件不存在或无内容返回 0）
     * @throws IOException JSONL 读取失败
     */
    public int loadFromJsonl(String scope) throws IOException {
        if (isBlank(scope)) {
            throw new IllegalArgumentException("scope(taskId) 不能为空");
        }
        Path jsonl = validator.resolveSecurePath("projects/" + scope + "/symbols.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            log.warn("[SymbolIndex] 符号产物不存在，索引为空: {}", jsonl);
            return 0;
        }

        List<UnifiedCodeSymbol> batch = new ArrayList<>();
        int loaded = 0;
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    batch.add(objectMapper.readValue(line, UnifiedCodeSymbol.class));
                    loaded++;
                } catch (Exception e) {
                    // 单行损坏不阻断整体加载（与切片2 单文件容灾同源）
                    log.warn("[SymbolIndex] 跳过损坏的符号行: {}", e.getMessage());
                }
                if (batch.size() >= 2000) {
                    registerAll(scope, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            registerAll(scope, batch);
        }
        log.info("[SymbolIndex] 符号索引加载完成: scope={}, symbols={}", scope, loaded);
        return loaded;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 按关键词 + 符号类型检索符号。
     *
     * @param scope 作用域；为空则跨分区聚合
     * @param query 关键词（匹配 symbolId / name，忽略大小写）；为空则仅按 kind 过滤
     * @param kind  CLASS / METHOD / FIELD ...；为空则不过滤
     */
    public List<UnifiedCodeSymbol> find(String scope, String query, String kind) {
        String q = query == null ? "" : query.trim().toLowerCase();
        String k = kind == null ? "" : kind.trim();

        List<UnifiedCodeSymbol> collected = new ArrayList<>();
        for (Partition partition : resolve(scope)) {
            for (UnifiedCodeSymbol symbol : partition.byId.values()) {
                if (!k.isEmpty() && !k.equalsIgnoreCase(symbol.kind())) {
                    continue;
                }
                if (!q.isEmpty()) {
                    String id = symbol.symbolId() == null ? "" : symbol.symbolId().toLowerCase();
                    String name = symbol.name() == null ? "" : symbol.name().toLowerCase();
                    if (!id.contains(q) && !name.contains(q)) {
                        continue;
                    }
                }
                collected.add(symbol);
                if (collected.size() >= MAX_QUERY_RESULTS) {
                    log.warn("[SymbolIndex] 查询结果超过上限 {}，已截断: query={}", MAX_QUERY_RESULTS, query);
                    return collected;
                }
            }
        }
        return collected;
    }

    /**
     * 反查"谁调用了该符号"——O(1) 倒排命中，精确优先、退化不用子串。
     */
    public List<String> callersOf(String scope, UnifiedCodeSymbol symbol) {
        if (symbol == null || isBlank(symbol.symbolId())) {
            return List.of();
        }
        String id = symbol.symbolId();

        // ① 精确：全限定签名键
        Set<String> exact = new LinkedHashSet<>();
        for (Partition partition : resolve(scope)) {
            Set<String> hit = partition.callersByKey.get(id);
            if (hit != null) {
                exact.addAll(hit);
            }
        }
        if (!exact.isEmpty()) {
            exact.remove(id); // 自调用不计入上游
            return sorted(exact);
        }

        // ② 退化：方法签名 / 裸方法名键（等值匹配，非子串）
        Set<String> keys = new LinkedHashSet<>();
        calleeKeys(id, keys);
        keys.remove(id);
        Set<String> fallback = new LinkedHashSet<>();
        for (Partition partition : resolve(scope)) {
            for (String key : keys) {
                Set<String> hit = partition.callersByKey.get(key);
                if (hit != null) {
                    fallback.addAll(hit);
                }
            }
        }
        fallback.remove(id);
        return sorted(fallback);
    }

    /**
     * 按 symbolId 精确取符号。
     */
    public Optional<UnifiedCodeSymbol> get(String scope, String symbolId) {
        if (isBlank(symbolId)) {
            return Optional.empty();
        }
        for (Partition partition : resolve(scope)) {
            UnifiedCodeSymbol symbol = partition.byId.get(symbolId);
            if (symbol != null) {
                return Optional.of(symbol);
            }
        }
        return Optional.empty();
    }

    /**
     * 作用域内符号总数；scope 为空时返回全局总数。
     */
    public int size(String scope) {
        int total = 0;
        for (Partition partition : resolve(scope)) {
            total += partition.byId.size();
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 把一条调用目标（全限定签名 / 方法签名 / 裸方法名）归一为精确匹配键集合。
     *
     * <p>例：{@code com.demo.PaymentService#executePayment(java.lang.String,int)} 归一出
     * {@code com.demo.PaymentService#executePayment(java.lang.String,int)}、
     * {@code executePayment(java.lang.String,int)}、{@code executePayment} 三个<b>精确</b>键。
     */
    private static void calleeKeys(String rawCallee, Set<String> out) {
        if (rawCallee == null) {
            return;
        }
        String callee = rawCallee.trim();
        if (callee.isEmpty()) {
            return;
        }
        out.add(callee);

        int hash = callee.indexOf('#');
        String afterHash = hash >= 0 ? callee.substring(hash + 1) : callee;
        out.add(afterHash);

        int paren = afterHash.indexOf('(');
        if (paren > 0) {
            out.add(afterHash.substring(0, paren));
        }
    }

    private Partition partitionOf(String scope) {
        return partitions.computeIfAbsent(isBlank(scope) ? "default" : scope, k -> new Partition());
    }

    private List<Partition> resolve(String scope) {
        if (isBlank(scope)) {
            return new ArrayList<>(partitions.values());
        }
        Partition partition = partitions.get(scope);
        return partition == null ? List.of() : List.of(partition);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
