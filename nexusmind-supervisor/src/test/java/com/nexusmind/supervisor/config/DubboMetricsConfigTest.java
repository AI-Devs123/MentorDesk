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
 * 切片 2 配置校验（离线）：4 个模块 yml 必须开启 {@code dubbo.metrics.enabled=true}，
 * 以闭环切片 1 的 P0-3（Dubbo Triple 指标跨进程暴露）。
 *
 * <p>运行期 CWD 为各模块根目录，故用 {@code ..} 上溯到兄弟模块读取其 yml。
 */
class DubboMetricsConfigTest {

    private static final String[] MODULES = {
            "nexusmind-gateway", "nexusmind-supervisor", "nexusmind-rag", "nexusmind-agent-workers"
    };

    @Test
    void dubboMetricsEnabledInAllModules() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String module : MODULES) {
            Path yml = Paths.get("..", module, "src", "main", "resources", "application.yml");
            assertTrue(Files.exists(yml), "缺失 yml 文件: " + yml.toAbsolutePath());
            List<PropertySource<?>> sources = loader.load("test-" + module, new FileSystemResource(yml));
            PropertySource<?> ps = sources.get(0);
            assertEquals("true", String.valueOf(ps.getProperty("dubbo.metrics.enabled")),
                    "模块 " + module + " 未开启 dubbo.metrics.enabled（P0-3 未闭环）");
        }
    }
}
