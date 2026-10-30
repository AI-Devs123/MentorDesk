package com.nexusmind.supervisor.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.agent.facade.CognitiveCancelResponse;
import com.nexusmind.agent.facade.CognitiveStreamChunk;
import com.nexusmind.agent.facade.CognitiveStreamRequest;
import com.nexusmind.agent.facade.DubboCognitiveWorkflowFacadeTriple;
import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.JournalReviewRequest;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.security.rbac.AgentRbacContext;
import com.nexusmind.supervisor.persona.PersonaProfileService;
import com.nexusmind.supervisor.service.JournalReviewFacade;
import com.nexusmind.supervisor.service.TutorFacade;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 认知协同 DAG 流式执行引擎 (supervisor 编排层)
 *
 * 拓扑: JournalAgent 结构化落盘 (串行主链)
 *       -> Persona 画像演变 (异步旁路，不阻断主链)
 *       -> TutorAgent 苏格拉底启发 (blockers 非空时条件分支)
 *       -> Supervisor 汇聚 done
 *
 * 切片4 评审修复项:
 * - 进程边界: journal/tutor 均经 @DubboReference 薄门面调用 agent-workers，
 *   不本地注入 agent 实现类 (P0-2)
 * - JDK 基线: 平台线程池替代 Thread.ofVirtual() (根 pom compiler=17) (P0-3)
 * - RBAC 透传: 调 TutorAgent 前 setCurrentAgent("tutorAgent")，
 *   否则 AgentRbacDubboFilter 因缺少角色上下文抛 403 (P1-3)
 * - 输入校验: DAG 入口复刻 JournalReviewRequest 长度约束，
 *   编排路径不再绕过切片2 校验 (P1-2)
 * - 取消语义诚实标注: interrupt 对阻塞中的 Dubbo RPC (socket 读) 无效，
 *   取消实际做到"停止推流 + 当前 RPC 返回后不再推进"，与 Week1
 *   AgentStreamingFacadeImpl 同款语义；真实耗时时限由 Dubbo 调用超时兜底
 *
 * userId 由 Gateway 从 JWT 提取后随请求透传 (不接受可伪造的裸 header)。
 */
@DubboService(interfaceClass = com.nexusmind.agent.facade.CognitiveWorkflowFacade.class)
public class CognitiveWorkflowFacadeImpl extends DubboCognitiveWorkflowFacadeTriple.CognitiveWorkflowFacadeImplBase {

    private static final Logger log = LoggerFactory.getLogger(CognitiveWorkflowFacadeImpl.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 任务中断标志: taskId -> isCancelled */
    private static final Map<String, AtomicBoolean> TASK_CANCELLATION_FLAGS = new ConcurrentHashMap<>();

    /** 运行中任务句柄: taskId -> Future (保留字段, 取消语义走线程 interrupt) */
    private static final Map<String, Future<?>> RUNNING_FUTURES = new ConcurrentHashMap<>();

    /** 运行中任务执行线程注册表: taskId -> Thread (收到取消后用于立即中断 worker) */
    private static final Map<String, Thread> RUNNING_THREADS = new ConcurrentHashMap<>();

    /** DAG 执行线程池 (平台线程: JDK 17 基线) */
    private final java.util.concurrent.ExecutorService dagExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "cognitive-dag");
                t.setDaemon(true);
                return t;
            });

    private final JournalReviewFacade journalReviewFacade;
    private final TutorFacade tutorFacade;
    private final PersonaProfileService personaProfileService;
    private final ObjectMapper objectMapper;

    public CognitiveWorkflowFacadeImpl(JournalReviewFacade journalReviewFacade,
                                       TutorFacade tutorFacade,
                                       PersonaProfileService personaProfileService,
                                       ObjectMapper objectMapper) {
        this.journalReviewFacade = journalReviewFacade;
        this.tutorFacade = tutorFacade;
        this.personaProfileService = personaProfileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void executeCognitiveStream(CognitiveStreamRequest request,
                                       StreamObserver<CognitiveStreamChunk> responseObserver) {
        String taskId = request.getTaskId();
        String rawContent = request.getRawContent();
        String userId = request.getUserId() != null ? request.getUserId() : "default_user";

        // P1-2: DAG 入口输入校验 (与 JournalReviewController 同源约束)
        if (rawContent == null || rawContent.isBlank()) {
            responseObserver.onError(new IllegalArgumentException("日记内容不能为空"));
            return;
        }
        if (rawContent.length() < JournalReviewRequest.MIN_LENGTH || rawContent.length() > JournalReviewRequest.MAX_LENGTH) {
            responseObserver.onError(new IllegalArgumentException(String.format(
                    "内容长度必须在 %d~%d 字符之间 (当前: %d)",
                    JournalReviewRequest.MIN_LENGTH, JournalReviewRequest.MAX_LENGTH, rawContent.length())));
            return;
        }

        AtomicBoolean isAborted = new AtomicBoolean(false);
        TASK_CANCELLATION_FLAGS.put(taskId, isAborted);

        Future<?> future = dagExecutor.submit(() -> {
            // 注册执行线程, 供 cancelCognitiveTask 收到取消信号后立即中断
            RUNNING_THREADS.put(taskId, Thread.currentThread());
            try {
                // Step 1: JournalAgent 三要素抽取 + Append-only 落盘
                emitProgress(responseObserver, taskId, "JournalAgent", "RUNNING",
                        "执行 Few-Shot 三要素结构化清洗与沙箱 Append-only 落盘...");
                if (isAborted.get()) {
                    return;
                }

                DataPayload payload = journalReviewFacade.reviewAndPersist(rawContent);
                JournalEntryDTO entry = payload.entry();

                emitProgress(responseObserver, taskId, "JournalAgent", "PROGRESS", String.format(
                        "日记已落盘 [%s]，提取突破点 %d 项，阻碍 %d 项，行动 %d 项",
                        payload.physicalPath(),
                        entry.extraction().breakthroughs().size(),
                        entry.extraction().blockers().size(),
                        entry.extraction().mvpActions().size()));

                emitData(responseObserver, taskId, "journal_result", payload);
                if (isAborted.get()) {
                    return;
                }

                // Step 2: Persona 画像演变 (异步旁路，不阻断主链)
                personaProfileService.evolvePersonaProfileAsync(userId, entry);
                emitProgress(responseObserver, taskId, "Supervisor", "PROGRESS",
                        "已触发个人认知画像与薄弱点评分增量更新。");

                // Step 3: TutorAgent 启发辨析 (blockers 非空时条件分支)
                if (entry.extraction().blockers().isEmpty()) {
                    emitProgress(responseObserver, taskId, "TutorAgent", "SKIPPED",
                            "今日未提取到技术阻碍，跳过苏格拉底辨析引导。");
                } else {
                    emitProgress(responseObserver, taskId, "TutorAgent", "RUNNING",
                            "基于阻碍根因，启动混淆概念诊断与苏格拉底思考题生成...");

                    var primaryBlocker = entry.extraction().blockers().get(0);
                    TutorReflectionPrompt tutorPrompt;
                    try {
                        // P1-3: 下游 RPC 前挂载 tutorAgent 角色上下文，
                        // AgentRbacDubboFilter 经 ClientAttachment 校验放行
                        AgentRbacContext.setCurrentAgent("tutorAgent");
                        tutorPrompt = tutorFacade.generateSocraticReflection(
                                primaryBlocker.symptom(),
                                primaryBlocker.rootCause(),
                                primaryBlocker.technicalDomain());
                    } finally {
                        AgentRbacContext.clearContext();
                    }

                    emitProgress(responseObserver, taskId, "TutorAgent", "PROGRESS", String.format(
                            "生成针对领域 [%s] 的启发式反思设问，已接入三段式折叠结构",
                            tutorPrompt.targetDomain()));

                    emitData(responseObserver, taskId, "tutor_prompt", tutorPrompt);
                }

                if (isAborted.get()) {
                    return;
                }

                // Step 4: DAG 闭环
                emitProgress(responseObserver, taskId, "Supervisor", "COMPLETED", "认知复盘与导师启发协同 DAG 执行完毕。");
                responseObserver.onCompleted();

            } catch (Exception e) {
                // interrupt 语义: 线程被唤醒后阻塞调用抛出的异常在此收敛;
                // 取消场景下 isAborted 已置位, 推流停止, 异常仅记录不外抛
                if (isAborted.get()) {
                    log.warn("[CognitiveDAG] 任务已取消, 执行链收敛, taskId={}, cause={}", taskId, e.toString());
                    responseObserver.onCompleted();
                } else {
                    log.error("[CognitiveDAG] 编排执行异常, taskId={}", taskId, e);
                    responseObserver.onError(e);
                }
            } finally {
                TASK_CANCELLATION_FLAGS.remove(taskId);
                RUNNING_THREADS.remove(taskId);
            }
        });
    }

    @Override
    public CognitiveCancelResponse cancelCognitiveTask(String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        Thread worker = RUNNING_THREADS.get(taskId);

        boolean found = flag != null;
        boolean interrupted = false;
        if (found && flag.compareAndSet(false, true)) {
            if (worker != null) {
                worker.interrupt();
                interrupted = true;
            }
            log.warn("[CognitiveDAG] RST_STREAM 已送达: taskId={}, interrupted={}", taskId, interrupted);
        }

        return CognitiveCancelResponse.newBuilder()
                .found(found)
                .interrupted(interrupted)
                .activeTaskCount(TASK_CANCELLATION_FLAGS.size())
                .build();
    }

    /**
     * 标记任务已取消 (TaskExecutionServiceImpl.abortTask 复用入口)
     */
    public static void markTaskCancelled(String taskId) {
        AtomicBoolean flag = TASK_CANCELLATION_FLAGS.get(taskId);
        if (flag != null) {
            flag.set(true);
        }
    }

    private void emitProgress(StreamObserver<CognitiveStreamChunk> observer, String taskId,
                              String agent, String status, String message) {
        String timestamp = "[" + LocalTime.now().format(TIME_FMT) + "]";
        CognitiveStreamChunk chunk = CognitiveStreamChunk.newBuilder()
                .frameType("progress")
                .taskId(taskId)
                .timestamp(timestamp)
                .agent(agent)
                .status(status)
                .message(message)
                .build();
        observer.onNext(chunk);
    }

    private void emitData(StreamObserver<CognitiveStreamChunk> observer, String taskId,
                          String eventType, Object payload) {
        // 快速失败: data 帧序列化异常必须中断 DAG (onError)，
        // 严禁吞掉异常静默丢帧——那会让前端停在"主链未完"的不一致状态
        try {
            CognitiveStreamChunk chunk = CognitiveStreamChunk.newBuilder()
                    .frameType("data")
                    .taskId(taskId)
                    .timestamp("[" + LocalTime.now().format(TIME_FMT) + "]")
                    .eventType(eventType)
                    .payloadJson(objectMapper.writeValueAsString(payload))
                    .build();
            observer.onNext(chunk);
        } catch (Exception e) {
            throw new IllegalStateException("data 帧序列化失败: eventType=" + eventType, e);
        }
    }
}
