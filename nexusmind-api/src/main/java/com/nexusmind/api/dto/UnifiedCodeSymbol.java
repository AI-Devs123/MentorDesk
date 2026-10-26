package com.nexusmind.api.dto;

import java.util.List;

/**
 * 语言无关的统一代码符号抽象模型（公共契约）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code parentId} 把 class / method 连成包含关系，使符号图可拓扑化而非离散节点；</li>
 *   <li>{@code language} 预留多语言（切片6 经 Tree-Sitter sidecar 归一 Python/TS）；</li>
 *   <li>{@code callees} 为方法调用解析后的<b>全限定目标签名</b>列表
 *       （如 {@code com.nexusmind.order.OrderService.checkout()}），
 *       无法解析时安全退化为裸方法名，保证提取流不中断；</li>
 *   <li>切片7 的 Milvus + BM25 + RRF 检索将直接消费此模型（含 callees 构建调用图边）。</li>
 * </ul>
 */
public record UnifiedCodeSymbol(
        /** 全限定标识，如 com.nexusmind.order.OrderService#checkout */
        String symbolId,
        /** 所属上层符号 ID（方法指向所属 class/interface），根节点为空 */
        String parentId,
        /** 语言标记：JAVA / PYTHON / TYPESCRIPT */
        String language,
        /** 符号简名，如 checkout */
        String name,
        /** CLASS / INTERFACE / METHOD / FIELD */
        String kind,
        /** 相对代码工作区的标准化路径 */
        String filePath,
        int startLine,
        int endLine,
        /** 完整方法/类签名 */
        String signature,
        /** 注释 / Javadoc */
        String docComment,
        /** 解析后的全限定目标签名列表（失败退化为裸方法名） */
        List<String> callees,
        /** 注解列表，如 @Component / @Override */
        List<String> annotations
) {
}
