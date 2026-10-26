package com.nexusmind.security.destructive;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 Diff 计算引擎（Week3 破坏性拦截与 CodeAgent {@code DiffInspectTool} 共用的标准实现）。
 *
 * <p>为避免在离线构建环境新增第三方依赖（如 java-diff-utils），此处以 LCS（最长公共子序列）
 * 动态规划自实现行级 unified diff，输出标准 {@code + / - /  } 文本与新增 / 删除 / 修改计数，
 * 并据改动规模映射 {@link RiskLevel}。大文件（超过 {@link #MAX_LCS} 行）自动降级为行数启发式，
 * 避免 O(N*M) 内存 / 时间尖刺。
 *
 * <p>该引擎直接填充了 {@link DestructiveActionAdvisor#generateChangePreview} 中标注为
 * {@code @Deprecated} 的 "TODO: 升级为完整的 Diff 生成逻辑，支持标准 unified diff 格式" 占位。
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    /** 超过该行数则降级为启发式行数统计，防止超大文件 O(N*M) 计算 */
    private static final int MAX_LCS = 4000;

    /**
     * 行级改动统计
     *
     * @param added    纯新增行数
     * @param deleted  纯删除行数
     * @param modified 替换行数（原行被改为不同内容的行数）
     */
    public record DiffStats(int added, int deleted, int modified) {
    }

    /**
     * 计算两块文本之间的行级改动统计。
     */
    public static DiffStats computeStats(List<String> original, List<String> revised) {
        int n = original.size();
        int m = revised.size();
        if (n == 0 && m == 0) {
            return new DiffStats(0, 0, 0);
        }
        if (n > MAX_LCS || m > MAX_LCS) {
            // 降级：无法精确 LCS 时按行数差估算
            int added = Math.max(0, m - n);
            int deleted = Math.max(0, n - m);
            return new DiffStats(added, deleted, 0);
        }

        int[][] dp = lcsMatrix(original, revised);
        // dp[i][j] = LCS(a[i..], b[j..])，因此必须<b>从前往后</b>回溯，才能逐个吃掉相同行
        int i = 0, j = 0;
        int added = 0, deleted = 0;
        while (i < n && j < m) {
            if (original.get(i).equals(revised.get(j))) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                deleted++;
                i++;
            } else {
                added++;
                j++;
            }
        }
        while (i < n) {
            deleted++;
            i++;
        }
        while (j < m) {
            added++;
            j++;
        }
        // 替换 = 成对的新增 / 删除，拆出为 modified
        int replace = Math.min(added, deleted);
        added -= replace;
        deleted -= replace;
        return new DiffStats(added, deleted, replace);
    }

    /**
     * 生成标准 unified diff 文本（精简 hunk 形式）。
     */
    public static String toUnifiedDiff(List<String> original, List<String> revised) {
        int n = original.size();
        int m = revised.size();
        StringBuilder sb = new StringBuilder();
        sb.append("@@ -").append(n).append(" +").append(m).append(" @@\n");

        if (n > MAX_LCS || m > MAX_LCS) {
            // 降级：全量罗列
            for (String s : original) {
                sb.append("- ").append(s).append('\n');
            }
            for (String s : revised) {
                sb.append("+ ").append(s).append('\n');
            }
            return sb.toString();
        }

        int[][] dp = lcsMatrix(original, revised);
        List<String> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (original.get(i).equals(revised.get(j))) {
                out.add("  " + original.get(i));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add("- " + original.get(i));
                i++;
            } else {
                out.add("+ " + revised.get(j));
                j++;
            }
        }
        while (i < n) {
            out.add("- " + original.get(i));
            i++;
        }
        while (j < m) {
            out.add("+ " + revised.get(j));
            j++;
        }
        for (String line : out) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 根据改动规模与是否触碰敏感路径评估风险等级。
     *
     * @param stats               改动统计
     * @param touchesSensitivePath 是否触碰安全 / 配置 / 构建等关键文件
     */
    public static RiskLevel assessRisk(DiffStats stats, boolean touchesSensitivePath) {
        if (touchesSensitivePath) {
            return RiskLevel.CRITICAL;
        }
        int churn = stats.added() + stats.deleted() + stats.modified();
        if (stats.deleted() >= 50 || churn >= 200) {
            return RiskLevel.HIGH;
        }
        // 任何非空改动至少 MEDIUM（最低风险档）
        return RiskLevel.MEDIUM;
    }

    private static int[][] lcsMatrix(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        return dp;
    }
}
