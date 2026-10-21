package com.nexusmind.gateway.controller;

import com.nexusmind.agent.facade.AgentStreamRequest;
import com.nexusmind.agent.facade.AgentStreamResponse;
import com.nexusmind.agent.facade.AgentStreamingFacade;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

@OpenAPIDefinition(info = @Info(title = "Agent Gateway API", version = "1.0.0", description = "Agent 网关 API，提供流式任务执行能力"))
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*")
@Tag(name = "Agent Gateway", description = "Agent 网关相关接口")
public class AgentGatewayController {

    @DubboReference
    private AgentStreamingFacade agentStreamingFacade;

    @Operation(summary = "流式执行 Agent 任务", description = "通过 SSE (Server-Sent Events) 流式返回 Agent 执行结果")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamAgentTask(
            @RequestParam(defaultValue = "测试指令") String prompt,
            @RequestParam(defaultValue = "Supervisor") String agent) {

        String taskId = UUID.randomUUID().toString();

        return Flux.<AgentStreamResponse>create(sink -> {
            AgentStreamRequest request = AgentStreamRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAgentName(agent)
                    .setPayload(prompt)
                    .build();

            agentStreamingFacade.executeTaskStream(request, new StreamObserver<AgentStreamResponse>() {
                @Override
                public void onNext(AgentStreamResponse data) {
                    sink.next(data);
                }

                @Override
                public void onError(Throwable throwable) {
                    sink.error(throwable);
                }

                @Override
                public void onCompleted() {
                    sink.complete();
                }
            });

            sink.onCancel(() -> {
                System.out.println("[Gateway] 前端 SSE 连接已主动取消, TaskId: " + taskId);
            });
        })
        .map(response -> ServerSentEvent.<String>builder()
                .id(response.getTaskId())
                .event("agent-chunk")
                .data(String.format("{\"timestamp\":%d,\"stage\":\"%s\",\"content\":\"%s\"}",
                        response.getTimestamp(), response.getStage(), response.getDeltaChunk()))
                .build())
        .timeout(Duration.ofSeconds(60));
    }
}