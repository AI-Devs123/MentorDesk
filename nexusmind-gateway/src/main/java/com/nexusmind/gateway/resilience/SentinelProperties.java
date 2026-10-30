package com.nexusmind.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 Sentinel 配置（绑定 yml 的 {@code sentinel.*}）。
 *
 * <p>仅描述「接资源名 + 阈值」的纯数据，规则加载逻辑在 {@link SentinelGatewayConfig}。
 * 嵌套 {@code FlowRuleDef}/{@code DegradeRuleDef} 与 Sentinel 原生 API 字段一一对应，
 * 由 {@code @ConfigurationProperties} 松绑定（kebab/camel 均可）。
 */
@ConfigurationProperties(prefix = "sentinel")
public class SentinelProperties {

    /** 主开关：false 时完全跳过 Sentinel（过滤器直通，不加载规则）。 */
    private boolean enabled = true;

    /** 不做限流/熔断的 URL 前缀（如 /actuator、/swagger-ui）。 */
    private List<String> excludedPaths = new ArrayList<>();

    /** 接入层流控规则（按 资源名=方法:路径 的 QPS 限流）。 */
    private List<FlowRuleDef> flowRules = new ArrayList<>();

    /** 熔断规则（按下游资源名的异常比例/慢调用比例熔断）。 */
    private List<DegradeRuleDef> degradeRules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }

    public List<FlowRuleDef> getFlowRules() {
        return flowRules;
    }

    public void setFlowRules(List<FlowRuleDef> flowRules) {
        this.flowRules = flowRules;
    }

    public List<DegradeRuleDef> getDegradeRules() {
        return degradeRules;
    }

    public void setDegradeRules(List<DegradeRuleDef> degradeRules) {
        this.degradeRules = degradeRules;
    }

    /** 流控规则定义：资源名 + 阈值(count) + 维度(grade)。 */
    public static class FlowRuleDef {
        private String resource;
        private double count = 100;
        /** 1=QPS（默认），0=线程数。 */
        private int grade = 1;

        public String getResource() {
            return resource;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public double getCount() {
            return count;
        }

        public void setCount(double count) {
            this.count = count;
        }

        public int getGrade() {
            return grade;
        }

        public void setGrade(int grade) {
            this.grade = grade;
        }
    }

    /** 熔断规则定义：资源名 + 维度(grade) + 阈值(count) + 最小请求数 + 统计窗口 + 熔断时长。 */
    public static class DegradeRuleDef {
        private String resource;
        /** 1=异常比例（默认），0=慢调用比例，2=异常数。 */
        private int grade = 1;
        private double count = 0.5;
        private int minRequestAmount = 5;
        private int statIntervalMs = 10000;
        private int timeWindowMs = 5000;

        public String getResource() {
            return resource;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public int getGrade() {
            return grade;
        }

        public void setGrade(int grade) {
            this.grade = grade;
        }

        public double getCount() {
            return count;
        }

        public void setCount(double count) {
            this.count = count;
        }

        public int getMinRequestAmount() {
            return minRequestAmount;
        }

        public void setMinRequestAmount(int minRequestAmount) {
            this.minRequestAmount = minRequestAmount;
        }

        public int getStatIntervalMs() {
            return statIntervalMs;
        }

        public void setStatIntervalMs(int statIntervalMs) {
            this.statIntervalMs = statIntervalMs;
        }

        public int getTimeWindowMs() {
            return timeWindowMs;
        }

        public void setTimeWindowMs(int timeWindowMs) {
            this.timeWindowMs = timeWindowMs;
        }
    }
}
