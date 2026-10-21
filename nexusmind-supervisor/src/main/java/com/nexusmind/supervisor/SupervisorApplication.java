package com.nexusmind.supervisor;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo // 激活 Dubbo 注解驱动（扫描 @DubboService）
public class SupervisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}