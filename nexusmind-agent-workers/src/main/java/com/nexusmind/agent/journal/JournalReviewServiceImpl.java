package com.nexusmind.agent.journal;

import com.nexusmind.agent.extraction.TagExtractor;
import com.nexusmind.agent.llm.LLMClient;
import com.nexusmind.agent.storage.AppendOnlyFileChannelWriter;
import com.nexusmind.agent.storage.SandboxPathValidator;
import com.nexusmind.api.dto.DataPayload;
import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.JournalEntryDTO.StructuredExtraction;
import com.nexusmind.api.dto.ProcessingStats;
import com.nexusmind.api.service.IJournalReviewService;
import com.nexusmind.security.rbac.AgentSecured;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日记审查服务实现 (agent-workers 层 - 核心能力库)
 *
 * 职责:
 * 1. 调度 LLM 进行三要素提取 (支持 Mock 降级)
 * 2. 调用文件写通道进行 Append-only 落盘
 * 3. 解析 LLM 响应
 * 4. 组装 DataPayload 响应
 *
 * 不涉及: HTTP 层、认证、请求校验 (这些由 supervisor/controller 负责)
 *
 * 导出方式: @DubboService 以公共契约 IJournalReviewService 注册到 Nacos，
 * supervisor 侧 JournalReviewFacade 通过 @DubboReference 远程调用——
 * 执行（含 LLM 调用与落盘）全部发生在 agent-workers 进程内。
 * 保留 @Service 使本类同时是本地 Spring bean，供 agent 进程内直接复用。
 */
@Service
@DubboService(interfaceClass = IJournalReviewService.class)
public class JournalReviewServiceImpl implements JournalReviewService {
    
    private static final Logger log = LoggerFactory.getLogger(JournalReviewServiceImpl.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;

    // 用于生成序列号的计数器
    private static final AtomicInteger sequenceCounter = new AtomicInteger(0);

    /**
     * 落盘序列化专用 Gson。
     * 必须为 LocalDate 注册 TypeAdapter：JDK 9+ 模块系统下 Gson 无法反射
     * java.time.LocalDate 的私有字段（year/month/day），裸 Gson.toJson()
     * 会抛 "Failed making field 'java.time.LocalDate#year' accessible"。
     * 这里统一按 ISO-8601 字符串读写，与 Spring Jackson 对 API 响应的序列化保持一致。
     */
    private static final com.google.gson.Gson ENTRY_GSON = new com.google.gson.GsonBuilder()
            .registerTypeAdapter(LocalDate.class, new com.google.gson.TypeAdapter<LocalDate>() {
                @Override
                public void write(com.google.gson.stream.JsonWriter out, LocalDate value) throws java.io.IOException {
                    out.value(value.toString());
                }

                @Override
                public LocalDate read(com.google.gson.stream.JsonReader in) throws java.io.IOException {
                    return LocalDate.parse(in.nextString());
                }
            })
            .create();
    
    @Autowired
    private LLMClient llmClient;
    
    @Autowired
    private JournalStructuredParser parser;
    
    @Autowired
    private TagExtractor tagExtractor;
    
    /**
     * 审查日记并落盘
     *
     * <p><b>RBAC（切片5 闭环修复）：</b>接口 {@code IJournalReviewService.reviewAndPersist}
     * 已声明 {@code @AgentSecured(allowedAgents={"supervisor","journalAgent"})}，由
     * AgentRbacDubboFilter 在 Provider 端强制。此处再补方法级注解做纵深防御，
     * 并约束：本实现绝不可被 codeAgent / tutorAgent 越权调用（日记属私密数据）。
     */
    @AgentSecured(allowedAgents = {"supervisor", "journalAgent"}, audit = true)
    @Override
    public DataPayload reviewAndPersist(String rawContent) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Step 1: 解析和提取
            JournalEntryDTO entry = parseAndExtract(rawContent);
            log.info("✅ [JournalReviewService] 三要素提取完成");
            
            // Step 2: 持久化
            String physicalPath = persistJournalEntry(entry);
            log.info("✅ [JournalReviewService] 文件落盘完成: {}", physicalPath);
            
            // Step 3: 组装响应
            long processingTime = System.currentTimeMillis() - startTime;
            ProcessingStats stats = new ProcessingStats(
                    entry.extraction().breakthroughs().size(),
                    entry.extraction().blockers().size(),
                    entry.extraction().mvpActions().size(),
                    processingTime
            );
            
            DataPayload payload = new DataPayload(entry, physicalPath, stats);
            log.info("✅ [JournalReviewService] 处理完成，耗时: {}ms", processingTime);
            
            return payload;
            
        } catch (Exception e) {
            log.error("❌ [JournalReviewService] 审查失败", e);
            throw new RuntimeException("日记审查失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析日记并提取三要素
     */
    @Override
    public JournalEntryDTO parseAndExtract(String rawContent) {
        log.info("📖 [JournalReviewService] 开始解析日记，长度: {} 字符", rawContent.length());
        
        // Step 1: 生成条目 ID (YYYY-MM-DD-###)
        LocalDate today = LocalDate.now();
        String entryId = generateEntryId(today);
        log.debug("📝 [JournalReviewService] 生成条目 ID: {}", entryId);
        
        // Step 2: 调用 LLM (支持 Mock 降级)
        log.info("🤖 [JournalReviewService] 调用 LLM 进行三要素提取...");
        String llmResponse = llmClient.callLlmOrFallback(rawContent);
        
        // Step 3: 解析 LLM 响应
        log.info("📝 [JournalReviewService] 解析 LLM 响应...");
        StructuredExtraction extraction = parser.parse(llmResponse);
        
        // Step 4: 提取标签
        log.info("🏷️ [JournalReviewService] 提取标签...");
        List<String> tags = tagExtractor.extractTags(rawContent);
        
        // Step 5: 构建条目
        long createdAt = System.currentTimeMillis();
        JournalEntryDTO entry = new JournalEntryDTO(
                entryId,
                rawContent,
                today,
                tags,
                extraction,
                createdAt
        );
        
        log.info("✅ [JournalReviewService] 日记解析完成: {} 突破, {} 阻碍, {} 行动, {} 标签",
                extraction.breakthroughs().size(),
                extraction.blockers().size(),
                extraction.mvpActions().size(),
                tags.size());
        
        return entry;
    }
    
    /**
     * 持久化日记条目 (使用 Append-only 文件通道)
     */
    @Override
    public String persistJournalEntry(JournalEntryDTO entry) {
        try {
            log.info("💾 [JournalReviewService] 开始落盘: {}", entry.entryId());
            
            // Step 1: 生成相对路径
            String relativePath = generateRelativePath(entry.recordDate(), entry.entryId());
            log.debug("📝 [JournalReviewService] 相对路径: {}", relativePath);
            
            // Step 2: 校验路径安全性
            Path filePath = SandboxPathValidator.validateAndResolve(relativePath);
            log.debug("📝 [JournalReviewService] 绝对路径: {}", filePath);
            
            // Step 2.5: 自愈目录结构（父目录不存在时逐级创建）
            // validateAndResolve 只做路径解析与穿越检查，不创建目录；
            // 若不先 ensureDirectory，appendToFile 会因父目录缺失抛 NoSuchFileException
            SandboxPathValidator.ensureDirectory(filePath);
            
            // Step 3: 组织文件内容 (Markdown 格式)
            String fileContent = buildFileContent(entry);
            
            // Step 4: 使用 Append-only 写通道写入
            AppendOnlyFileChannelWriter.appendToFile(filePath, fileContent);
            log.info("✅ [JournalReviewService] 落盘成功");
            
            return relativePath;
            
        } catch (Exception e) {
            log.error("❌ [JournalReviewService] 落盘失败", e);
            throw new RuntimeException("文件落盘失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成条目 ID: YYYY-MM-DD-###
     */
    private String generateEntryId(LocalDate date) {
        String dateStr = date.format(DATE_FORMATTER);
        
        // 使用原子计数器保证序列号唯一性
        int sequence = sequenceCounter.incrementAndGet() % 1000;
        String seq = String.format("%03d", sequence);
        
        return dateStr + "-" + seq;
    }
    
    /**
     * 生成相对路径: YYYY/MM/DD/entry-{id}.json
     */
    private String generateRelativePath(LocalDate recordDate, String entryId) {
        String year = recordDate.format(DateTimeFormatter.ofPattern("yyyy"));
        String month = recordDate.format(DateTimeFormatter.ofPattern("MM"));
        String day = recordDate.format(DateTimeFormatter.ofPattern("dd"));
        
        return String.format("%s/%s/%s/entry-%s.json", year, month, day, entryId);
    }
    
    /**
     * 构建文件内容 (Markdown + JSON 混合格式)
     */
    private String buildFileContent(JournalEntryDTO entry) {
        // 转换为 JSON 字符串（使用带 LocalDate TypeAdapter 的 Gson，勿改回裸 Gson）
        String entryJson = ENTRY_GSON.toJson(entry);
        
        return String.format("""
                
                <!-- ENTRY_START: %s -->
                ```json
                %s
                ```
                <!-- ENTRY_END -->
                """, entry.entryId(), entryJson);
    }
    
    /**
     * 获取自动注入的 TagExtractor
     */
    @Autowired
    public void setTagExtractor(TagExtractor tagExtractor) {
        this.tagExtractor = tagExtractor;
    }
}
