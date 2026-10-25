package com.nexusmind.api.dto;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 个人认知画像：存储习惯偏置、认知盲区得分、注意力周期标签
 * 不可变设计，确保状态一致性
 */
public record PersonaProfile(
    String profileId,              // 画像唯一标识
    long updatedAt,                // 最近更新时间
    List<CognitiveBias> biases,    // 认知偏置观察项
    Map<String, Double> blindSpots, // 认知薄弱点/盲区评分 (Domain -> 0.0~1.0)
    List<String> attentionTags,    // 注意力周期标签 (如: "High-Energy: Morning")
    int totalReflectiveDays        // 累计复盘天数
) implements Serializable {
    
    public PersonaProfile {
        Objects.requireNonNull(profileId, "profileId 不能为空");
        
        biases = biases == null ? List.of() : List.copyOf(biases);
        attentionTags = attentionTags == null ? List.of() : List.copyOf(attentionTags);
        
        // 深层复制 blindSpots Map：先创建新 HashMap，再转为不可修改
        if (blindSpots == null) {
            blindSpots = Map.of();
        } else {
            // 校验盲区评分范围 [0.0, 1.0]
            blindSpots.values().forEach(score -> {
                if (score < 0.0 || score > 1.0) {
                    throw new IllegalArgumentException("盲区评分必须在 [0.0, 1.0] 范围内，当前值: " + score);
                }
            });
            // 创建新 Map 副本，防止外部修改影响内部状态
            blindSpots = Collections.unmodifiableMap(new java.util.HashMap<>(blindSpots));
        }
        
        // 校验复盘天数非负
        if (totalReflectiveDays < 0) {
            throw new IllegalArgumentException("累计复盘天数不能为负数");
        }
    }

    /**
     * 认知偏置项
     */
    public record CognitiveBias(
        String biasName,          // 偏置类型 (如: "乐观时间预估", "局部优化偏执")
        String manifestation,     // 具体表现证据
        int frequency,            // 历史累计命中频次
        BiasConfidence confidenceLevel  // 置信度
    ) implements Serializable {
        
        public CognitiveBias {
            Objects.requireNonNull(biasName, "biasName 不能为空");
            Objects.requireNonNull(manifestation, "manifestation 不能为空");
            Objects.requireNonNull(confidenceLevel, "confidenceLevel 不能为空");
            
            if (frequency < 0) {
                throw new IllegalArgumentException("命中频次不能为负数");
            }
        }
    }

    /**
     * 置信度枚举
     */
    public enum BiasConfidence {
        LOW, MEDIUM, HIGH
    }

    /**
     * 工厂方法：创建新的空画像
     */
    public static PersonaProfile newProfile(String profileId) {
        return new PersonaProfile(
            profileId,
            System.currentTimeMillis(),
            List.of(),
            Map.of(),
            List.of(),
            0
        );
    }
}
