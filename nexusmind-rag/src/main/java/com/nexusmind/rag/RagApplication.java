package com.nexusmind.rag;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NexusMind 混合检索微服务（Week 7 切片 1 骨架，切片 5 修复 Dubbo 导出）。
 *
 * <p><b>切片 5 生产缺陷修复</b>：此前本类是<b>唯一</b>缺少 {@link EnableDubbo} 的模块
 * （supervisor / agent-workers / gateway 均已标注）。缺了它，Dubbo 的
 * {@code ServiceAnnotationPostProcessor} 不会被注册，{@code @DubboService} <b>从不生效</b>
 * —— {@code HybridSearchServiceImpl} 一直只是个普通 Spring Bean，从未真正导出 Triple 服务。
 *
 * <p>该缺陷被长期掩盖的原因：网关侧 {@code @DubboReference(..., check = false)} 在
 * Provider 缺失时<b>不报启动错</b>，导致"检索链路看起来是通的"，直到切片 5 首次做真正的
 * 跨进程 Triple 调用才暴露（{@code UNIMPLEMENTED: Service not found}）。
 *
 * <p>{@code web-application-type=none} 仍然正确：本服务是 Dubbo Provider，经 Triple
 * （自身端口）暴露，不需要 HTTP 端点，故不绑 Netty HTTP 端口。
 */
@EnableDubbo
@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
