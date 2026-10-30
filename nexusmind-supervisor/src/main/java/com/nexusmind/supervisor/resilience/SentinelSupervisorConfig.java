package com.nexusmind.supervisor.resilience;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;

/**
 * Supervisor Sentinel 规则加载配置。
 *
 * <p>启动时把 yml 中的 {@code sentinel.flow-rules} / {@code sentinel.degrade-rules}
 * 翻译为 Sentinel 原生 {@link FlowRule} / {@link DegradeRule} 并注入全局 {@code *Manager}。
 * 主开关关闭时直接跳过（servlet 过滤器亦会直通，绝不做任何拦截）。
 */
@Configuration
@EnableConfigurationProperties(SentinelProperties.class)
public class SentinelSupervisorConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelSupervisorConfig.class);

    private final SentinelProperties props;

    public SentinelSupervisorConfig(SentinelProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void loadRules() {
        if (!props.isEnabled()) {
            log.info("[Sentinel] supervisor 熔断限流主开关关闭，跳过规则加载（直通）");
            return;
        }
        FlowRuleManager.loadRules(buildFlowRules());
        DegradeRuleManager.loadRules(buildDegradeRules());
        log.info("[Sentinel] supervisor 规则已加载：flow={}, degrade={}",
                props.getFlowRules().size(), props.getDegradeRules().size());
    }

    private List<FlowRule> buildFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        for (SentinelProperties.FlowRuleDef def : props.getFlowRules()) {
            if (def.getResource() == null || def.getResource().isBlank()) {
                continue;
            }
            FlowRule rule = new FlowRule(def.getResource());
            rule.setCount(def.getCount());
            rule.setGrade(def.getGrade());
            rule.setLimitApp("default");
            rules.add(rule);
        }
        return rules;
    }

    private List<DegradeRule> buildDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        for (SentinelProperties.DegradeRuleDef def : props.getDegradeRules()) {
            if (def.getResource() == null || def.getResource().isBlank()) {
                continue;
            }
            DegradeRule rule = new DegradeRule(def.getResource());
            rule.setGrade(def.getGrade());
            rule.setCount(def.getCount());
            rule.setMinRequestAmount(def.getMinRequestAmount());
            rule.setStatIntervalMs(def.getStatIntervalMs());
            // Sentinel 的 timeWindow 单位为秒，yml 用毫秒表述，此处换算
            rule.setTimeWindow(def.getTimeWindowMs() / 1000);
            rules.add(rule);
        }
        return rules;
    }
}
