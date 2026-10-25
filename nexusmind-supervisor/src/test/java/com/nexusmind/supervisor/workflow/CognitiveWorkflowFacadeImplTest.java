package com.nexusmind.supervisor.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexusmind.agent.facade.CognitiveStreamChunk;
import com.nexusmind.agent.facade.CognitiveStreamRequest;
import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.PersonaProfile;
import com.nexusmind.api.dto.ProcessingStats;
import com.nexusmind.api.dto.TutorReflectionPrompt;
import com.nexusmind.supervisor.persona.PersonaProfileService;
import com.nexusmind.supervisor.service.JournalReviewFacade;
import com.nexusmind.supervisor.service.TutorFacade;
import org.apache.dubbo.common.stream.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CognitiveWorkflowFacadeImpl DAG 编排测试
 *
 * 重点覆盖切片4 评审修复点与编排语义:
 * - 输入校验前置 (P1-2: 编排路径不绕过切片2 约束)
 * - 帧序列: journal RUNNING -> journal PROGRESS -> journal_result data
 *   -> persona PROGRESS -> tutor ... -> COMPLETED -> onCompleted
 * - blockers 为空跳过 TutorAgent
 * - 取消标志响应 (cancelCognitiveTask)
 *
 * 门面用 Mockito mock，Dubbo 引用不参与本测试 (进程内直接验证编排逻辑)。
 * ObjectMapper 对齐 Spring 容器行为: 注册 JavaTimeModule 且禁用时间戳，
 * 否则 JournalEntryDTO 的 LocalDate 字段序列化抛 InvalidDefinitionException
 * (该场景正是 data 帧快速失败设计的回归用例)。
 */
class CognitiveWorkflowFacadeImplTest {

    private JournalReviewFacade journalReviewFacade;
    private TutorFacade tutorFacade;
    private PersonaProfileService personaProfileService;
    private CognitiveWorkflowFacadeImpl dag;

    private final List<CognitiveStreamChunk> received = new ArrayList<>();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private Throwable capturedError;

    @BeforeEach
    void setUp() throws Exception {
        journalReviewFacade = mock(JournalReviewFacade.class);
        tutorFacade = mock(TutorFacade.class);
        personaProfileService = mock(PersonaProfileService.class);
        ObjectMapper springLikeMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        dag = new CognitiveWorkflowFacadeImpl(journalReviewFacade, tutorFacade,
                personaProfileService, springLikeMapper);
    }

    /** 采集帧的 StreamObserver */
    private StreamObserver<CognitiveStreamChunk> collector() {
        return new StreamObserver<>() {
            @Override
            public void onNext(CognitiveStreamChunk chunk) {
                received.add(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                capturedError = throwable;
                errorCount.incrementAndGet();
            }

            @Override
            public void onCompleted() {
                completedCount.incrementAndGet();
            }
        };
    }

    private DataPayload payloadWithBlockers() {
        var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("打通 Triple"),
                List.of(new JournalEntryDTO.BlockerItem(
                        "前端关闭后后端线程未释放的现象描述", "doOnCancel 未挂载", "WebFlux")),
                List.of("补 3 个单测"));
        var entry = new JournalEntryDTO("entry-001", "日记原文", LocalDate.of(2026, 10, 25),
                List.of("Dubbo"), extraction, System.currentTimeMillis());
        return new DataPayload(entry, "2026/10/25/entry-001.json",
                new ProcessingStats(1, 1, 1, 100));
    }

    private TutorReflectionPrompt sampleTutorPrompt() {
        return new TutorReflectionPrompt(
                "tutor-abc123", "Concurrency", "原理剖析回填", "同步阻塞 vs 异步事件驱动",
                List.of(new TutorReflectionPrompt.ReflectionQuestion(
                        1, "WebFlux", "Virtual Thread", "RST_STREAM 传播差异?")),
                List.of());
    }

    private void awaitCompletion() throws InterruptedException {
        // 等待 DAG 异步执行收敛 (onCompleted 或 onError)
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (completedCount.get() > 0 || errorCount.get() > 0) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("DAG 全链帧序列: journal -> persona -> tutor -> COMPLETED -> onCompleted")
    void shouldEmitFullFrameSequence() throws Exception {
        when(journalReviewFacade.reviewAndPersist(anyString())).thenReturn(payloadWithBlockers());
        when(tutorFacade.generateSocraticReflection(anyString(), anyString(), anyString()))
                .thenReturn(sampleTutorPrompt());

        var request = CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-1").setRawContent("今天打通 Triple 但取消信号有问题").setUserId("u1").build();
        dag.executeCognitiveStream(request, collector());
        awaitCompletion();

        assertEquals(1, completedCount.get(), "必须正常完成");
        assertEquals(0, errorCount.get());

        List<String> stages = received.stream().map(CognitiveStreamChunk::getAgent).toList();
        assertTrue(stages.contains("JournalAgent"));
        assertTrue(stages.contains("TutorAgent"));

        // data 帧两类齐全
        List<String> eventTypes = received.stream()
                .filter(c -> "data".equals(c.getFrameType()))
                .map(CognitiveStreamChunk::getEventType).toList();
        assertEquals(List.of("journal_result", "tutor_prompt"), eventTypes);

        // journal_result 帧负载含落盘路径
        String journalPayload = received.stream()
                .filter(c -> "journal_result".equals(c.getEventType()))
                .findFirst().orElseThrow().getPayloadJson();
        assertTrue(journalPayload.contains("2026/10/25/entry-001.json"));

        // tutor_prompt 帧负载含辨析题
        String tutorPayload = received.stream()
                .filter(c -> "tutor_prompt".equals(c.getEventType()))
                .findFirst().orElseThrow().getPayloadJson();
        assertTrue(tutorPayload.contains("RST_STREAM 传播差异"));

        // Persona 旁路被触发且不阻塞主链
        verify(personaProfileService, times(1)).evolvePersonaProfileAsync(eq("u1"), any());

        // 末帧为 Supervisor COMPLETED
        var last = received.get(received.size() - 1);
        assertEquals("Supervisor", last.getAgent());
        assertEquals("COMPLETED", last.getStatus());
    }

    @Test
    @DisplayName("blockers 为空: 跳过 TutorAgent 且不产生 tutor_prompt 帧")
    void shouldSkipTutorWhenNoBlockers() throws Exception {
        var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("平稳推进"), List.of(), List.of("继续推进"));
        var entry = new JournalEntryDTO("entry-002", "日记", LocalDate.of(2026, 10, 25),
                List.of(), extraction, System.currentTimeMillis());
        when(journalReviewFacade.reviewAndPersist(anyString()))
                .thenReturn(new DataPayload(entry, "p.json", new ProcessingStats(1, 0, 1, 50)));

        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-2").setRawContent("今天一切顺利，按计划推进").setUserId("u1").build(), collector());
        awaitCompletion();

        assertEquals(1, completedCount.get());
        assertTrue(received.stream().noneMatch(c -> "tutor_prompt".equals(c.getEventType())));
        assertTrue(received.stream().anyMatch(c -> "SKIPPED".equals(c.getStatus())));
        verify(tutorFacade, never()).generateSocraticReflection(any(), any(), any());
    }

    @Test
    @DisplayName("输入校验: 空内容与超长内容直接 onError，不进入 DAG")
    void shouldRejectInvalidInput() throws Exception {
        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-3").setRawContent("").build(), collector());
        awaitCompletion();

        assertEquals(1, errorCount.get());
        assertTrue(received.isEmpty(), "校验失败不得推任何帧");
        verify(journalReviewFacade, never()).reviewAndPersist(any());

        // 超长
        received.clear();
        errorCount.set(0);
        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-4").setRawContent("x".repeat(10001)).build(), collector());
        awaitCompletion();

        assertEquals(1, errorCount.get());
        verify(journalReviewFacade, never()).reviewAndPersist(any());
    }

    @Test
    @DisplayName("RbacContext 角色透传: Tutor 调用期间挂载 tutorAgent，结束即清理")
    void shouldSetTutorRoleAroundRpcCall() throws Exception {
        when(journalReviewFacade.reviewAndPersist(anyString())).thenReturn(payloadWithBlockers());
        // 捕获 tutor 调用期间的 RBAC 上下文角色
        List<String> rolesDuringCall = new ArrayList<>();
        doAnswer((InvocationOnMock inv) -> {
            rolesDuringCall.add(com.nexusmind.security.rbac.AgentRbacContext.getCurrentAgent());
            return sampleTutorPrompt();
        }).when(tutorFacade).generateSocraticReflection(anyString(), anyString(), anyString());

        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-5").setRawContent("今天打通 Triple 但取消信号有问题").setUserId("u1").build(), collector());
        awaitCompletion();

        assertEquals(List.of("tutorAgent"), rolesDuringCall,
                "Tutor RPC 期间必须挂载 tutorAgent 角色上下文");
        assertNull(com.nexusmind.security.rbac.AgentRbacContext.getCurrentAgent(),
                "Tutor 调用后必须清理角色上下文");
    }

    @Test
    @DisplayName("取消响应: cancelCognitiveTask 命中后返回 found=true 且计数归位")
    void shouldHandleCancel() throws Exception {
        // 用慢 journal 调用占住任务，确保取消时任务在册
        CountDownLatch release = new CountDownLatch(1);
        when(journalReviewFacade.reviewAndPersist(anyString())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return payloadWithBlockers();
        });

        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-6").setRawContent("今天打通 Triple 但取消信号有问题").setUserId("u1").build(), collector());

        // 等任务注册进 FLAGS
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Object flags = ReflectionTestUtils.getField(dag, "TASK_CANCELLATION_FLAGS");
            if (flags instanceof java.util.Map<?, ?> map && map.containsKey("COG-TEST-6")) {
                break;
            }
            Thread.sleep(10);
        }

        var resp = dag.cancelCognitiveTask("COG-TEST-6");
        assertTrue(resp.isFound(), "在册任务必须命中");
        assertTrue(resp.isInterrupted(), "必须下发 interrupt");

        // 未知任务
        var miss = dag.cancelCognitiveTask("COG-NOPE");
        assertFalse(miss.isFound());

        release.countDown();
        awaitCompletion();
    }

    @Test
    @DisplayName("异常路径: journal 调用抛错 -> onError 且不推 tutor 帧")
    void shouldPropagateJournalError() throws Exception {
        when(journalReviewFacade.reviewAndPersist(anyString()))
                .thenThrow(new RuntimeException("Dubbo 超时"));

        dag.executeCognitiveStream(CognitiveStreamRequest.newBuilder()
                .setTaskId("COG-TEST-7").setRawContent("今天打通 Triple 但取消信号有问题").setUserId("u1").build(), collector());
        awaitCompletion();

        assertEquals(1, errorCount.get());
        assertNotNull(capturedError);
        verify(tutorFacade, never()).generateSocraticReflection(any(), any(), any());
    }
}
