package com.nexusmind.supervisor.evolution;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.api.dto.WebCurateResult;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 任务完成事件（切片 1 抽象载体）。
 *
 * <p>四个编排入口（Journal / Tutor / Code / WebCurator）返回类型各不相同，
 * 后置进化钩子需要一份统一契约。本 record 由各门面/编排类在远程调用
 * <b>成功返回之后</b>构造，承载「本次任务产出的上下文」供自进化扫描消费。
 *
 * <p>collection 映射（对齐 rag 的集合白名单 journal / code / web_kb）：
 * <ul>
 *   <li>journalAgent → journal（日记追加写）</li>
 *   <li>codeAgent    → code（代码 AST 符号）</li>
 *   <li>tutorAgent   → journal（苏格拉底反思源自日记复盘上下文）</li>
 *   <li>webCurator   → web_kb（网页萃取知识库）</li>
 * </ul>
 *
 * <p>切片 1 仅做「基础设施 + 扫描日志 + Redis 状态暂存」；切片 2 再基于
 * {@code FusedCandidate.rrfScore} 做 0.65~0.85 阈值带的新旧关联抽取。
 */
public record TaskCompletionEvent(
        /** 任务唯一标识（journal/tutor 用 UUID，code/webCurator 复用 requestId） */
        String taskId,
        /** 触发本次任务的 Agent 角色（journalAgent / codeAgent / tutorAgent / webCurator） */
        String agentRole,
        /** 目标 rag 集合名（journal / code / web_kb） */
        String collection,
        /** 本次任务产出的代表性文本（作为相似度探测 query） */
        String producedContent,
        /** 本次新落盘资产的 docId（可选，切片 2 反哺关联点用） */
        String newDocId
) {
    public static TaskCompletionEvent forJournal(String rawContent) {
        return new TaskCompletionEvent(
                UUID.randomUUID().toString(), "journalAgent", "journal", rawContent, null);
    }

    public static TaskCompletionEvent forCode(String taskId, List<CodeAnalysisProgress> frames) {
        String content = frames.stream()
                .map(f -> f.stage() + "@" + (f.currentFile() == null ? "" : f.currentFile()))
                .collect(Collectors.joining(" | "));
        return new TaskCompletionEvent(taskId, "codeAgent", "code", content, null);
    }

    public static TaskCompletionEvent forTutor(TutorReflectionPrompt prompt) {
        String content = prompt.contextSummary() + " " + prompt.coreConflict() + " "
                + prompt.questions().stream()
                .map(ReflectionQuestionText::of)
                .collect(Collectors.joining(" "));
        return new TaskCompletionEvent(
                UUID.randomUUID().toString(), "tutorAgent", "journal", content, null);
    }

    public static TaskCompletionEvent forWebCurator(String taskId, WebCurateResult result) {
        String content = (result.canonicalUrl() == null ? "" : result.canonicalUrl()) + " "
                + result.docId() + " chunks=" + result.chunkCount();
        return new TaskCompletionEvent(taskId, "webCurator", "web_kb", content, result.docId());
    }

    /** 从 ReflectionQuestion 提取问题正文，避免在该 record 内依赖嵌套 record 结构细节。 */
    private static final class ReflectionQuestionText {
        static String of(TutorReflectionPrompt.ReflectionQuestion q) {
            return q.questionText();
        }
    }
}
