package com.nexusmind.security.destructive;

import java.lang.annotation.*;

/**
 * 标记破坏性操作（删除、修改敏感配置等）
 * 被此注解标记的方法在执行时需要用户Token确认
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DestructiveOperation {
    /**
     * 操作描述（用于日志和用户提示）
     */
    String value() default "破坏性操作";
    
    /**
     * 是否需要Token确认（默认true）
     */
    boolean requireConfirmation() default true;
}
