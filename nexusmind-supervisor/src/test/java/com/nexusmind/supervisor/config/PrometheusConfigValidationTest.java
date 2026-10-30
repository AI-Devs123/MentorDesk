package com.nexusmind.supervisor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 2 配置校验（离线）：{@code deploy/observability/prometheus/prometheus.yml}
 * 必须含 3 个抓取 job（gateway / supervisor / pushgateway），否则 Grafana 看不到指标。
 *
 * <p>运行期 CWD 为模块根目录，仓库存放于 {@code ../..}，故用相对路径上溯。
 */
class PrometheusConfigValidationTest {

    private static final Path PROMETHEUS_YML =
            Paths.get("..", "deploy", "observability", "prometheus", "prometheus.yml");

    private static final String[] EXPECTED_JOBS = {
            "nexusmind-gateway", "nexusmind-supervisor", "nexusmind-pushgateway"
    };

    @Test
    void prometheusScrapeJobsPresent() throws Exception {
        assertTrue(Files.exists(PROMETHEUS_YML), "缺失 prometheus.yml: " + PROMETHEUS_YML.toAbsolutePath());

        var loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("prom", new FileSystemResource(PROMETHEUS_YML));
        PropertySource<?> ps = sources.get(0);

        List<String> jobNames = new ArrayList<>();
        EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
        for (String name : eps.getPropertyNames()) {
            if (name.matches("scrape_configs\\[\\d+\\]\\.job_name")) {
                jobNames.add(String.valueOf(ps.getProperty(name)));
            }
        }

        for (String expected : EXPECTED_JOBS) {
            assertTrue(jobNames.contains(expected),
                    "prometheus.yml 缺少抓取 job: " + expected + "（当前: " + jobNames + "）");
        }
    }
}
