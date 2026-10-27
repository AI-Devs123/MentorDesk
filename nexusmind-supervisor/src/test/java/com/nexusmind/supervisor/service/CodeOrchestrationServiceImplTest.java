package com.nexusmind.supervisor.service;

import com.nexusmind.api.dto.CodeAnalysisProgress;
import com.nexusmind.api.dto.CodeAnalysisRequest;
import com.nexusmind.api.service.ICodeAnalysisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CodeOrchestrationServiceImpl 单测：验证「编排层只做下发与聚合，不碰 AST」的契约。
 */
@ExtendWith(MockitoExtension.class)
class CodeOrchestrationServiceImplTest {

    @Mock
    private ICodeAnalysisService codeAnalysisService;

    @InjectMocks
    private CodeOrchestrationServiceImpl orchestrationService;

    @Test
    @DisplayName("orchestrateCodeAnalysis 应将请求下发到 CodeAgent 并原样返回进度帧")
    void shouldDelegateToCodeAgentAndReturnFrames() {
        CodeAnalysisRequest request = new CodeAnalysisRequest("T-1", "nexusmind-agent-workers", "JAVA");
        CodeAnalysisProgress frame = new CodeAnalysisProgress(
                "T-1", "2026-10-27T00:00:00Z", "COMPLETED", "", 3, 3, "{}");
        when(codeAnalysisService.analyzeProject(request)).thenReturn(List.of(frame));

        List<CodeAnalysisProgress> result = orchestrationService.orchestrateCodeAnalysis(request);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("COMPLETED", result.get(0).stage());
        // 编排层必须真的把请求透传给 CodeAgent，不得本地模拟
        verify(codeAnalysisService, times(1)).analyzeProject(request);
    }

    @Test
    @DisplayName("编排层不得改写 taskId / 帧内容（进度帧由 CodeAgent 权威产生）")
    void shouldNotAlterFrames() {
        CodeAnalysisRequest request = new CodeAnalysisRequest("T-42", "proj", "JAVA");
        CodeAnalysisProgress original = new CodeAnalysisProgress(
                "T-42", "ts", "INDEXED", "A.java", 1, 5, "[{\"symbol\":\"Foo\"}]");
        when(codeAnalysisService.analyzeProject(request)).thenReturn(List.of(original));

        List<CodeAnalysisProgress> result = orchestrationService.orchestrateCodeAnalysis(request);

        assertEquals("T-42", result.get(0).taskId());
        assertEquals("INDEXED", result.get(0).stage());
        assertEquals("[{\"symbol\":\"Foo\"}]", result.get(0).payloadJson());
        verify(codeAnalysisService).analyzeProject(request);
    }
}
