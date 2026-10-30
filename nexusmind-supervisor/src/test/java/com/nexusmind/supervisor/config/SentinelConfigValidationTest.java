package com.nexusmind.supervisor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 3 配置校验（离线）：gateway 与 supervisor 的 yml 必须开启 {@code sentinel.enabled=true}，
 * 且至少各含一条 flow-rule 与 degrade-rule（下游熔断），否则限流/熔断规则不会被加载。
 *
 * <p>运行期 CWD 为 supervisor 模块根目录：gateway yml 用 {@code ..} 上溯读取，supervisor yml 为当前模块。
 */
class SentinelConfigValidationTest {

    private static final Path GATEWAY_YML =
            Paths.get("..", "nexusmind-gateway", "src", "main", "resources", "application.yml");
    private static final Path SUPERVISOR_YML =
            Paths.get("src", "main", "resources", "application.yml");

    @Test
    void sentinelEnabledInGatewayAndSupervisor() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        assertTrue(Files.exists(GATEWAY_YML), "缺失 gateway yml: " + GATEWAY_YML.toAbsolutePath());
        List<PropertySource<?>> gwSources = loader.load("gw", new FileSystemResource(GATEWAY_YML));
        PropertySource<?> gw = gwSources.get(0);
        assertEquals("true", String.valueOf(gw.getProperty("sentinel.enabled")),
                "gateway 未开启 sentinel.enabled");

        assertTrue(Files.exists(SUPERVISOR_YML), "缺失 supervisor yml");
        List<PropertySource<?>> svSources = loader.load("sv", new FileSystemResource(SUPERVISOR_YML));
        PropertySource<?> sv = svSources.get(0);
        assertEquals("true", String.valueOf(sv.getProperty("sentinel.enabled")),
                "supervisor 未开启 sentinel.enabled");
    }

    @Test
    void sentinelRulesPresent() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        PropertySource<?> gw = loader.load("gw", new FileSystemResource(GATEWAY_YML)).get(0);
        PropertySource<?> sv = loader.load("sv", new FileSystemResource(SUPERVISOR_YML)).get(0);

        // gateway：接入层流控 + 下游熔断预留
        assertTrue(gw.getProperty("sentinel.flow-rules[0].resource") != null,
                "gateway 缺少 sentinel.flow-rules（接入层限流未配置）");
        assertTrue(gw.getProperty("sentinel.degrade-rules[0].resource") != null,
                "gateway 缺少 sentinel.degrade-rules");

        // supervisor：接入层流控 + 下游 Dubbo 熔断（journalReviewService）
        assertTrue(sv.getProperty("sentinel.flow-rules[0].resource") != null,
                "supervisor 缺少 sentinel.flow-rules");
        assertTrue(sv.getProperty("sentinel.degrade-rules[0].resource") != null,
                "supervisor 缺少 sentinel.degrade-rules（下游熔断未配置）");
        assertEquals("downstream:journalReviewService",
                String.valueOf(sv.getProperty("sentinel.degrade-rules[0].resource")),
                "supervisor 下游熔断资源名应与 JournalReviewFacade 使用的 resource 对齐");
    }
}
