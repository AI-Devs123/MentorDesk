package com.nexusmind.security.destructive;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 破坏性操作注解
 * 标记需要人工二阶段确认的方法
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @DestructiveOperation(
 *     type = DestructiveOperationType.FILE_DELETE,
 *     riskLevel = RiskLevel.HIGH,
 *     description = "删除用户上传的文件"
 * )
 * public void deleteUserFile(String filePath) {
 *     // 实际删除逻辑
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DestructiveOperation {
    /**
     * 操作类型
     */
    DestructiveOperationType type();
    
    /**
     * 风险等级，默认为 HIGH
     */
    RiskLevel riskLevel() default RiskLevel.HIGH;
    
    /**
     * 操作描述，用于前端展示
     */
    String description() default "";
    
    /**
     * 是否需要生成 Diff 预览（仅对文件操作有效）
     */
    boolean requiresDiff() default true;
}
