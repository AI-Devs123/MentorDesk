package com.nexusmind.api.rag.dto;

import java.io.Serializable;

/**
 * 混合检索请求参数（切片 3 契约）。
 *
 * <p>通过 Dubbo Triple 在 Gateway 与 nexusmind-rag 之间传输，故必须 {@link Serializable}。
 *
 * @param query        检索自然语句
 * @param collection   目标集合（journal / code / web_kb），受 RBAC 白名单约束
 * @param topK         稠密与稀疏单路召回数（默认 20~30）
 * @param fusedTopN    RRF 融合后截断数量（默认 5~10）
 * @param enableRerank 是否启用慢路径精排（切片 4 落地，当前保留契约位、默认 false 走纯 RRF 快路径）
 *
 * <p><b>序列化约定（切片 5 修复）</b>：{@code enableRerank} 必须是<b>包装类型</b> {@link Boolean}，
 * 不能是基本类型 {@code boolean}。Dubbo Triple 默认 hessian2 反序列化 Java {@code record} 时，
 * 会把 record 的 {@code final boolean} 分量当成装箱 {@link Boolean} 再回写 {@code final boolean}
 * 字段，抛出 {@code HessianFieldException: Boolean cannot be assigned to 'boolean'}（实测）。
 * 生产协议 {@code preferSerialization="fastjson2,hessian2"} 把 hessian2 列为兜底，一旦协商到
 * hessian2 此缺陷即触发，故必须以包装类型从根上消除。fastjson2 下两种写法都可用，包装类型兼容两者。
 */
public record HybridSearchRequest(
        String query,
        String collection,
        int topK,
        int fusedTopN,
        Boolean enableRerank
) implements Serializable {
}
