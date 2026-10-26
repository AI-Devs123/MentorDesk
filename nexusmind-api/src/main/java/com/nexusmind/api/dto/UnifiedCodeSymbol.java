package com.nexusmind.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 语言无关的统一代码符号抽象模型（公共契约）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code parentId} 把 class / method / field 连成包含关系，使符号图可拓扑化而非离散节点；</li>
 *   <li>{@code language} 预留多语言（切片6 经 Tree-Sitter sidecar 归一 Python/TS）；</li>
 *   <li>{@code kind} 取值：CLASS / INTERFACE / ENUM / RECORD / METHOD / CONSTRUCTOR / FIELD；</li>
 *   <li>{@code callees} 为方法调用解析后的<b>全限定目标签名</b>列表
 *       （如 {@code com.nexusmind.order.OrderService.checkout(java.lang.Long)}），
 *       无法解析时安全退化为裸方法名，保证提取流不中断；</li>
 *   <li>{@code superTypes} 优先存放全限定名（如 {@code java.lang.Runnable}），解析失败降级为原名；</li>
 *   <li>切片7 的 Milvus + BM25 + RRF 检索将直接消费此模型（含 callees 构建调用图边）。</li>
 * </ul>
 */
public record UnifiedCodeSymbol(
        /** 全限定标识，如 com.nexusmind.order.OrderService#checkout(java.lang.Long) */
        String symbolId,
        /** 所属上层符号 ID（方法/字段指向所属 class，内部类指向外层 class，根节点为空） */
        String parentId,
        /** 语言标记：JAVA / PYTHON / TYPESCRIPT */
        String language,
        /** 符号简名，如 checkout */
        String name,
        /** CLASS / INTERFACE / ENUM / RECORD / METHOD / CONSTRUCTOR / FIELD */
        String kind,
        /** 相对代码工作区的标准化路径 */
        String filePath,
        int startLine,
        int endLine,
        /** 修饰符全量：public, static, final, abstract 等 */
        List<String> modifiers,
        /** 方法返回类型 / 字段类型（如 java.util.concurrent.CompletableFuture<Void>） */
        String returnType,
        /** 方法参数签名列表（仅类型用于区分重载） */
        List<MethodParam> parameters,
        /** 继承与实现的接口/父类全限定名（仅针对类/接口/枚举） */
        List<String> superTypes,
        /** 完整可读声明（如 public UserDTO findById(Long id)） */
        String signature,
        /** Javadoc 清洗版（去 /**、*、*\/ 标记） */
        String docComment,
        /** 解析后的全限定目标签名列表（失败退化为裸方法名） */
        List<String> callees,
        /** 注解列表，如 @Component / @Override */
        List<String> annotations
) implements Serializable {

    /** 方法参数描述子：名字与类型（切片2 新增，支撑入参建模与重载区分） */
    public record MethodParam(
            String name,
            String type
    ) implements Serializable {}
}
