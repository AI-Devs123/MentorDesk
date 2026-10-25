package com.nexusmind.agent.journal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nexusmind.api.dto.JournalEntryDTO.StructuredExtraction;
import com.nexusmind.api.dto.JournalEntryDTO.BlockerItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 日记结构化解析器
 * 负责从 LLM 响应中提取并校验三要素，确保数据合规
 *
 * 显式指定 Bean 名：同模块曾存在同名类（旧 worker 布局），
 * 沿用默认 Bean 名会触发 ConflictingBeanDefinitionException
 */
@Component("journalReviewParser")
public class JournalStructuredParser {
    
    private static final Logger log = LoggerFactory.getLogger(JournalStructuredParser.class);
    private static final Gson gson = new Gson();
    
    // 正则：剥离 ```json 标记
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```",
            Pattern.DOTALL
    );
    
    /**
     * 解析 LLM 响应为结构化数据
     * 
     * 流程:
     * 1. 剥离 ```json 标记
     * 2. 解析 JSON
     * 3. 校验三要素 (breakthroughs ≥1, mvpActions ≥1)
     * 4. 校验 blockers 字段完整性
     * 5. 返回规范化的 StructuredExtraction
     * 
     * @param llmResponse LLM 原始响应
     * @return 结构化的提取结果
     */
    public static StructuredExtraction parse(String llmResponse) {
        try {
            // Step 1: 剥离 Markdown 代码块 (如果有)
            String jsonStr = stripJsonMarkdown(llmResponse);
            jsonStr = jsonStr.trim();
            
            log.debug("📝 [Parser] 清洁化后的 JSON: {}", jsonStr.length() + " 字符");
            
            // Step 2: 解析 JSON
            JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
            
            // Step 3: 提取三要素
            List<String> breakthroughs = parseStringArray(json, "breakthroughs");
            List<BlockerItem> blockers = parseBlockers(json.getAsJsonArray("blockers"));
            List<String> mvpActions = parseStringArray(json, "mvpActions");
            
            // Step 4: 校验最小要求
            validateMinimumRequirements(breakthroughs, mvpActions);
            
            log.info("✅ [Parser] 解析成功: {} 突破, {} 阻碍, {} 行动", 
                    breakthroughs.size(), blockers.size(), mvpActions.size());
            
            return new StructuredExtraction(breakthroughs, blockers, mvpActions);
            
        } catch (Exception e) {
            log.error("❌ [Parser] 解析失败: {}", e.getMessage());
            throw new IllegalArgumentException("JSON 解析或校验失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 剥离 ```json 标记
     * 处理可能的 Markdown 代码块包裹
     */
    private static String stripJsonMarkdown(String content) {
        // 尝试匹配 ```json ... ``` 模式
        var matcher = JSON_BLOCK_PATTERN.matcher(content);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            log.debug("📝 [Parser] 检测到 Markdown 代码块，提取内容");
            return extracted;
        }
        
        // 如果没有代码块，直接返回
        return content;
    }
    
    /**
     * 解析字符串数组
     */
    private static List<String> parseStringArray(JsonObject json, String key) {
        List<String> result = new ArrayList<>();
        
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                JsonArray array = json.getAsJsonArray(key);
                
                for (int i = 0; i < array.size(); i++) {
                    if (!array.get(i).isJsonNull()) {
                        String text = array.get(i).getAsString().trim();
                        if (!text.isEmpty()) {
                            result.add(text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Parser] 解析 {} 数组失败: {}", key, e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 解析阻碍项数组
     * 每个阻碍必须包含 symptom, rootCause, technicalDomain 三个字段
     */
    private static List<BlockerItem> parseBlockers(JsonArray blockersArray) {
        List<BlockerItem> result = new ArrayList<>();
        
        if (blockersArray == null) {
            return result;
        }
        
        try {
            for (int i = 0; i < blockersArray.size(); i++) {
                try {
                    JsonObject blocker = blockersArray.get(i).getAsJsonObject();
                    
                    String symptom = blocker.get("symptom").getAsString().trim();
                    String rootCause = blocker.get("rootCause").getAsString().trim();
                    String technicalDomain = blocker.get("technicalDomain").getAsString().trim();
                    
                    // 校验字段不为空
                    if (symptom.isEmpty() || rootCause.isEmpty() || technicalDomain.isEmpty()) {
                        log.warn("⚠️ [Parser] 阻碍项[{}] 包含空字段，跳过", i);
                        continue;
                    }
                    
                    // 校验字段长度
                    if (symptom.length() < 10 || symptom.length() > 200) {
                        log.warn("⚠️ [Parser] 阻碍项[{}] symptom 长度不符 ({})", i, symptom.length());
                        continue;
                    }
                    
                    result.add(new BlockerItem(symptom, rootCause, technicalDomain));
                    
                } catch (Exception e) {
                    log.warn("⚠️ [Parser] 解析阻碍项[{}] 失败: {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Parser] 解析阻碍数组失败: {}", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 校验最小要求
     * - breakthroughs 至少 1 项
     * - mvpActions 至少 1 项
     */
    private static void validateMinimumRequirements(List<String> breakthroughs, List<String> mvpActions) {
        if (breakthroughs.isEmpty()) {
            throw new IllegalArgumentException("breakthroughs 必须至少有 1 项");
        }
        
        if (mvpActions.isEmpty()) {
            throw new IllegalArgumentException("mvpActions 必须至少有 1 项");
        }
    }
}
