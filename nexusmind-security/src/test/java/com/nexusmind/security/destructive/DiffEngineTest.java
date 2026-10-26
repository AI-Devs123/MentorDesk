package com.nexusmind.security.destructive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一 Diff 引擎测试（Week3 破坏性拦截与 CodeAgent DiffInspectTool 共用）。
 */
class DiffEngineTest {

    @Test
    @DisplayName("纯追加: 只计 added")
    void countsPureAppend() {
        DiffEngine.DiffStats stats = DiffEngine.computeStats(
                List.of("a", "b"), List.of("a", "b", "c"));

        assertEquals(1, stats.added());
        assertEquals(0, stats.deleted());
        assertEquals(0, stats.modified());
    }

    @Test
    @DisplayName("等量替换: 计为 modified，而非互不相关的 add/delete")
    void countsInPlaceReplacementAsModified() {
        DiffEngine.DiffStats stats = DiffEngine.computeStats(
                List.of("a", "b", "c"), List.of("a", "X", "c"));

        assertEquals(1, stats.modified(), "一行被改应识别为修改，而不是新增+删除各一");
        assertEquals(0, stats.added());
        assertEquals(0, stats.deleted());
    }

    @Test
    @DisplayName("完全相同: 无任何改动")
    void detectsNoChange() {
        DiffEngine.DiffStats stats = DiffEngine.computeStats(
                List.of("a", "b"), List.of("a", "b"));

        assertEquals(0, stats.added());
        assertEquals(0, stats.deleted());
        assertEquals(0, stats.modified());
    }

    @Test
    @DisplayName("unified diff 文本含标准 + / - 行标记")
    void rendersUnifiedDiff() {
        String diff = DiffEngine.toUnifiedDiff(List.of("a", "b"), List.of("a", "c"));

        assertTrue(diff.startsWith("@@ -2 +2 @@"), "应包含统一 diff 头，实际: " + diff);
        assertTrue(diff.contains("- b"), "应含删除行标记");
        assertTrue(diff.contains("+ c"), "应含新增行标记");
    }

    @Test
    @DisplayName("风险评级: 敏感路径 CRITICAL、大面积删除 HIGH、普通改动 MEDIUM")
    void assessesRisk() {
        DiffEngine.DiffStats small = new DiffEngine.DiffStats(1, 0, 1);
        DiffEngine.DiffStats heavy = new DiffEngine.DiffStats(0, 60, 0);

        assertEquals(RiskLevel.MEDIUM, DiffEngine.assessRisk(small, false));
        assertEquals(RiskLevel.HIGH, DiffEngine.assessRisk(heavy, false));
        assertEquals(RiskLevel.CRITICAL, DiffEngine.assessRisk(small, true),
                "触碰关键文件时，即使改动很小也必须升级为 CRITICAL");
    }
}
