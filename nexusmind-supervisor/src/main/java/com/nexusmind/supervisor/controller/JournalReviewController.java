package com.nexusmind.supervisor.controller;

import com.nexusmind.api.dto.JournalReviewRequest;
import com.nexusmind.api.dto.JournalReviewResponse;
import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.supervisor.service.JournalReviewFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 日记审查控制器
 * 处理日记复盘的审查和落盘请求
 * 
 * 职责:
 * 1. HTTP 层处理 (请求/响应)
 * 2. 基本校验 (字数、认证)
 * 3. 调用 agent-workers 的核心能力
 */
@RestController
@RequestMapping("/api/v1/journal")
public class JournalReviewController {
    
    private static final Logger log = LoggerFactory.getLogger(JournalReviewController.class);
    
    @Autowired
    private JournalReviewFacade journalReviewFacade;
    
    /**
     * 日记审查与落盘端点
     *
     * 认证由 JwtAuthenticationFilter 统一负责（无 Token 在过滤器层即返回 401，
     * 到不了本方法），此处不再重复检查 Authorization header。
     *
     * @param request 日记审查请求
     * @return 审查结果和落盘信息
     */
    @PostMapping("/review")
    public ResponseEntity<JournalReviewResponse> reviewJournal(
            @RequestBody JournalReviewRequest request) {

        log.info("📥 [JournalReview] 收到日记审查请求，长度: {}",
                request.rawContent().length());

        try {
            // 1. 输入校验
            Map<String, String> validationErrors = validateRequest(request);
            if (!validationErrors.isEmpty()) {
                log.warn("❌ [JournalReview] 输入校验失败: {}", validationErrors);

                Map<String, Object> errorData = new HashMap<>();
                errorData.put("validationErrors", validationErrors);

                return ResponseEntity.badRequest().body(
                        new JournalReviewResponse(
                                false,
                                null,
                                "日记内容不符合要求"
                        )
                );
            }

            // 2. 调用 agent-workers 的核心服务
            long startTime = System.currentTimeMillis();
            DataPayload dataPayload = journalReviewFacade.reviewAndPersist(request.rawContent());
            long processingTime = System.currentTimeMillis() - startTime;
            
            log.info("✅ [JournalReview] 处理成功，耗时: {}ms", processingTime);
            
            return ResponseEntity.ok(
                    new JournalReviewResponse(
                            true,
                            dataPayload,
                            "日记已成功审查并落盘"
                    )
            );
            
        } catch (Exception e) {
            log.error("❌ [JournalReview] 处理异常", e);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new JournalReviewResponse(
                            false,
                            null,
                            "日记处理失败: " + e.getMessage()
                    )
            );
        }
    }
    
    /**
     * 验证请求
     */
    private Map<String, String> validateRequest(JournalReviewRequest request) {
        Map<String, String> errors = new HashMap<>();
        
        if (request.rawContent() == null || request.rawContent().trim().isEmpty()) {
            errors.put("rawContent", "日记内容不能为空");
            return errors;
        }
        
        int length = request.rawContent().length();
        if (length < JournalReviewRequest.MIN_LENGTH) {
            errors.put("rawContent", 
                    "内容长度必须不少于 " + JournalReviewRequest.MIN_LENGTH + " 字符（当前: " + length + "）");
        }
        
        if (length > JournalReviewRequest.MAX_LENGTH) {
            errors.put("rawContent", 
                    "内容长度不能超过 " + JournalReviewRequest.MAX_LENGTH + " 字符（当前: " + length + "）");
        }
        
        return errors;
    }
}
