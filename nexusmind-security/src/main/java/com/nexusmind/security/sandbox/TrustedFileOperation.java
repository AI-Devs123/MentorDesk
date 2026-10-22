package com.nexusmind.security.sandbox;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 信任的文件操作标注
 * 
 * 用于标注那些已经过安全审计且确实需要直接使用 File API 的方法
 * 被标注的方法可以跳过 FileInstantiationGuard 的拦截
 * 
 * 使用场景：
 * 1. 与第三方库交互，库要求 File 参数
 * 2. 读写系统文件（/etc/hosts 等）
 * 3. 临时文件处理
 * 
 * 注意：任何标注此注解的方法必须经过安全审计
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrustedFileOperation {
    
    /**
     * 说明为什么这个方法需要直接使用 File API
     */
    String reason() default "Security reviewed and approved";
    
    /**
     * 审计人员
     */
    String reviewedBy() default "";
    
    /**
     * 审计日期
     */
    String reviewDate() default "";
}
