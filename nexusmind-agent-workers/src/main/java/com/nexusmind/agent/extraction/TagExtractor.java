package com.nexusmind.agent.extraction;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 标签提取器 - 技术关键词自动识别
 * 从日记内容中识别技术关键词，用于分类和检索
 */
@Component
public class TagExtractor {
    
    // 技术关键词库 (本地维护，可扩展)
    private static final Set<String> TECH_KEYWORDS = Set.of(
            // 框架和库
            "Dubbo", "Spring", "SpringBoot", "WebFlux", "Spring Cloud", "RabbitMQ",
            "Kafka", "Redis", "MySQL", "PostgreSQL", "MongoDB", "Elasticsearch",
            
            // 技术概念
            "Concurrency", "Thread", "Async", "Stream", "Reactive", "SSE",
            "HTTP2", "gRPC", "Triple", "REST", "API", "WebSocket",
            "Microservices", "Service Mesh", "Docker", "Kubernetes", "K8s",
            
            // 编程语言和工具
            "Java", "Python", "JavaScript", "TypeScript", "Go", "Rust",
            "Maven", "Gradle", "Git", "Jenkins", "GitHub",
            
            // 测试和监控
            "JUnit", "Mockito", "Testing", "UnitTest", "Integration", "E2E",
            "Monitoring", "Logging", "Metrics", "Tracing", "APM",
            
            // 数据和 AI
            "RAG", "LLM", "AI", "Machine Learning", "NLP", "Vector",
            "Embedding", "ChatGPT", "Claude", "Transformer",
            
            // 其他常用术语
            "Architecture", "Design Pattern", "Refactoring", "Performance",
            "Security", "Authentication", "Authorization", "RBAC",
            "Cache", "Message Queue", "Event Driven", "CQRS"
    );
    
    /**
     * 从文本中提取标签
     * 
     * @param content 文本内容
     * @return 提取出的标签列表 (最多 5 个)
     */
    public List<String> extractTags(String content) {
        Set<String> foundTags = new LinkedHashSet<>();
        
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        
        String lowerContent = content.toLowerCase();
        
        // 逐个检查关键词
        for (String keyword : TECH_KEYWORDS) {
            if (matchKeyword(lowerContent, keyword)) {
                foundTags.add(keyword);
                // 最多提取 5 个标签
                if (foundTags.size() >= 5) {
                    break;
                }
            }
        }
        
        // 如果没有找到关键词，返回通用标签
        if (foundTags.isEmpty()) {
            foundTags.add("General");
        }
        
        return new ArrayList<>(foundTags);
    }
    
    /**
     * 检查关键词是否在内容中出现
     * 使用单词边界匹配避免子串匹配
     */
    private boolean matchKeyword(String content, String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        
        // 构建单词边界匹配的正则表达式
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(lowerKeyword) + "\\b",
                Pattern.CASE_INSENSITIVE);
        
        return pattern.matcher(content).find();
    }
}
