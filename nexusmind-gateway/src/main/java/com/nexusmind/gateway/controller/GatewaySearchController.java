package com.nexusmind.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusmind.api.rag.dto.HybridSearchRequest;
import com.nexusmind.api.rag.dto.HybridSearchResponse;
import com.nexusmind.api.rag.service.IHybridSearchService;
import com.nexusmind.security.rbac.AgentRbacContext;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 混合检索网关 SSE 桥接（切片 3）。
 *
 * <p>把前端的标准 SSE 请求桥接到后端 nexusmind-rag 的 Dubbo Triple 混合检索服务，并以
 * {@code text/event-stream} 透出进度帧（START / SEARCHING / FUSED / RESULT / COMPLETED），
 * 同时把 RBAC 越权异常翻译成 {@code SECURITY_VIOLATION} 事件。
 *
 * <p><b>关键修正（P1）</b>：原草稿在 {@code Flux.create} 的 emitter 中<b>同步</b>调用阻塞的 Dubbo RPC，
 * 会卡死 Netty 事件循环线程。这里把阻塞调用 {@code offload} 到 {@code Schedulers.boundedElastic()}；
 * 由于 {@link AgentRbacContext} / RpcContext 的 attachment 是 ThreadLocal，必须在 worker 线程上设置，
 * 才能随 Triple 调用透传到 Provider。
 */
@RestController
@RequestMapping("/api/v1/search")
public class GatewaySearchController {

    private static final Logger log = LoggerFactory.getLogger(GatewaySearchController.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @DubboReference(protocol = "tri", version = "1.0.0", check = false)
    private IHybridSearchService hybridSearchService;

    /**
     * 响应序列化（RESULT 帧 JSON 化）。required=false：在无 Spring 上下文的 {@code bindToController} 测试里
     * 没有 ObjectMapper bean，此时回落到字段初始化值；生产上下文里 Spring Boot 仍会注入其配置好的实例。
     */
    @Autowired(required = false)
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试注入点：在 {@code bindToController} 切片下 Dubbo 不创建 ReferenceBean（无注册中心），
     * 通过此 setter 注入 mock，使 SSE 行为可在不依赖 Nacos 的情况下被验证。
     */
    void setHybridSearchService(IHybridSearchService s) {
        this.hybridSearchService = s;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> searchStream(
            @RequestParam String query,
            @RequestParam String collection,
            @RequestHeader(value = "X-Agent-Role", defaultValue = "supervisor") String agentRole,
            @RequestParam(defaultValue = "5") int topN) {

        return Flux.<ServerSentEvent<String>>create(sink -> {
            String requestId = UUID.randomUUID().toString();

            // 进度帧（轻量，直接在事件循环线程发射，无阻塞）
            sink.next(buildSseEvent("START", String.format(
                    "[%s] Supervisor → 接收到混合检索指令: %s (Collection: %s, Role: %s)",
                    LocalTime.now().format(TIME_FMT), query, collection, agentRole)));
            sink.next(buildSseEvent("SEARCHING", String.format(
                    "[%s] SearchEngine → 启动 BM25 + HNSW 双通道并发检索...",
                    LocalTime.now().format(TIME_FMT))));

            // 阻塞的 Dubbo RPC + Lucene 检索统一 offload 到 boundedElastic
            Disposable disposable = Mono.fromCallable(() -> {
                        // 必须在 worker 线程上设置 attachment（RpcContext 为 ThreadLocal）
                        AgentRbacContext.setCurrentAgent(agentRole);
                        HybridSearchRequest request =
                                new HybridSearchRequest(query, collection, 20, topN, false);
                        return hybridSearchService.hybridSearch(request);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> AgentRbacContext.clearContext())
                    .subscribe(
                            response -> emitResult(sink, requestId, response),
                            err -> emitError(sink, err)
                    );

            // 客户端断开 SSE 时撤销后台检索（等价于 RST_STREAM）
            sink.onCancel(disposable::dispose);
        }).delayElements(Duration.ofMillis(30)); // 保证前端打字机渲染可观测性
    }

    private void emitResult(FluxSink<ServerSentEvent<String>> sink,
                            String requestId, HybridSearchResponse response) {
        try {
            sink.next(buildSseEvent("FUSED", String.format(
                    "[%s] RRF → 融合完成，耗时 %d ms，命中候选: %d 条",
                    LocalTime.now().format(TIME_FMT), response.elapsedMs(), response.candidates().size())));
            String payloadJson = objectMapper.writeValueAsString(response);
            sink.next(ServerSentEvent.<String>builder()
                    .id(requestId).event("RESULT").data(payloadJson).build());
            sink.next(buildSseEvent("COMPLETED", String.format(
                    "[%s] Supervisor → 检索流程圆满结束", LocalTime.now().format(TIME_FMT))));
            sink.complete();
        } catch (Exception e) {
            sink.next(buildSseEvent("ERROR", "[系统异常] 响应序列化失败: " + e.getMessage()));
            sink.complete();
        }
    }

    private void emitError(FluxSink<ServerSentEvent<String>> sink, Throwable err) {
        if (err instanceof RpcException) {
            RpcException rpcEx = (RpcException) err;
            if (rpcEx.getCode() == RpcException.FORBIDDEN_EXCEPTION) {
                sink.next(buildSseEvent("SECURITY_VIOLATION", "[SecurityGuard 拦截] " + rpcEx.getMessage()));
                sink.complete();
                return;
            }
        }
        if (err instanceof SecurityException) {
            sink.next(buildSseEvent("SECURITY_VIOLATION", "[SecurityGuard 拦截] " + err.getMessage()));
        } else {
            sink.next(buildSseEvent("ERROR", "[系统异常] " + err.getMessage()));
        }
        sink.complete();
    }

    private ServerSentEvent<String> buildSseEvent(String event, String message) {
        return ServerSentEvent.<String>builder().event(event).data(message).build();
    }
}
