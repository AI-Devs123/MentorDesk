package com.nexusmind.agent.curator.chunker;

import com.nexusmind.agent.curator.model.CuratedDocumentChunk;
import com.nexusmind.agent.curator.model.DocMetadata;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自适应语义分块器：按「Markdown 标题层级 → 代码块原子 → 自然段落」三级聚合切块。
 * <p>
 * 相比机械按字符硬截断，这里保证三件事：
 * <ol>
 *   <li><b>代码块绝不腰斩</b>——围栏按「起始围栏 + 同长闭合围栏」<b>配对</b>扫描，
 *       代码块整体作为一个不可分割的原子单元；块内出现 {@code ```} 作为示例内容时也不会被误判为闭合；</li>
 *   <li><b>标题不与正文割裂</b>——标题行不做独立单元，而是作为<b>紧随其后的正文块的头部前缀</b>一起聚合，
 *       避免出现「标题单独成块、正文在下一块」的语义断裂；</li>
 *   <li><b>重叠区不引入残片</b>——Overlap 只从上一个块的<b>普通段落尾部</b>提取，
 *       若尾部是代码块则跳过（否则下一块会以无开围栏的代码残片开头，反而制造非闭合围栏）。</li>
 * </ol>
 * <p>
 * 窗口口径：目标 {@value #TARGET_TOKENS} tokens / 上限 {@value #MAX_TOKENS} tokens，
 * 通过 {@link TokenEstimator} 折算为字符预算，全部换算集中在 TokenEstimator 一处。
 */
@Component
public class AdaptiveSemanticChunker {

    /** 目标块大小（tokens）。 */
    private static final int TARGET_TOKENS = 700;

    /** 单块上限（tokens）。 */
    private static final int MAX_TOKENS = 800;

    /** 上下文重叠区（tokens）。 */
    private static final int OVERLAP_TOKENS = 100;

    /** 围栏语言标识后的可选内容，用于识别 ```java 这类开围栏。 */
    private static final String FENCE = "```";

    private final TokenEstimator tokenEstimator;

    public AdaptiveSemanticChunker(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 执行分块。
     *
     * @param docId     文档全局唯一标识
     * @param markdown  清洗后的纯净 Markdown
     * @param metadata  文档级元数据（会挂载到每个片段）
     * @param originUrl 原始地址
     * @return 片段列表；输入为空时返回空列表
     */
    public List<CuratedDocumentChunk> chunkDocument(String docId, String markdown,
                                                   DocMetadata metadata, String originUrl) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        DocMetadata safeMeta = metadata == null ? DocMetadata.fallback("") : metadata;

        List<Unit> units = splitIntoUnits(markdown);
        List<String> rawChunks = aggregate(units);

        int total = rawChunks.size();
        List<CuratedDocumentChunk> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String content = rawChunks.get(i);
            Map<String, Object> metaMap = buildMetadata(safeMeta, originUrl, i, total);
            result.add(new CuratedDocumentChunk(
                    generateChunkId(docId, i), docId, i, total, content, metaMap));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 第一步：切分为原子语义单元
    // ------------------------------------------------------------------

    /**
     * 语义单元：要么是一整个代码块（原子不可分），要么是「标题前缀 + 若干正文行」的普通块。
     *
     * @param text       单元文本
     * @param codeBlock  是否为代码块单元
     * @param heading    该单元起始的标题行（无标题时为空串），供 Overlap 与调试图观测
     */
    private record Unit(String text, boolean codeBlock, String heading) {
    }

    /**
     * 按行扫描切分单元。
     * <p>
     * 关键：围栏判定用<b>配对</b>而非布尔翻转——记录开围栏的字符长度与语言标识，
     * 只有遇到「长度相同且无语言标识」的围栏才算闭合。这样 Markdown 里演示
     * {@code ```} 语法时不会被误当成闭合围栏，代码块不会被切碎。
     */
    private List<Unit> splitIntoUnits(String markdown) {
        List<Unit> units = new ArrayList<>();
        String[] lines = markdown.split("\\r?\\n", -1);

        StringBuilder current = new StringBuilder();   // 当前普通块累积
        String currentHeading = "";
        StringBuilder codeBlock = null;                // 非 null 表示正在代码块内
        int openFenceLength = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // --- 代码块内 ---
            if (codeBlock != null) {
                codeBlock.append(line).append('\n');
                if (isClosingFence(trimmed, openFenceLength)) {
                    units.add(new Unit(trimLeadingTrailingBlank(codeBlock.toString()), true, currentHeading));
                    codeBlock = null;
                    openFenceLength = 0;
                }
                continue;
            }

            // --- 遇到开围栏 ---
            if (isFence(trimmed)) {
                // 先把已累积的普通块收尾（代码块不与其后的正文混在同一单元）
                if (!current.isEmpty()) {
                    units.add(new Unit(current.toString().trim(), false, currentHeading));
                    current.setLength(0);
                    currentHeading = "";
                }
                openFenceLength = fenceLength(trimmed);
                codeBlock = new StringBuilder(line).append('\n');
                continue;
            }

            // --- 标题行：记录为「下一块的前缀」，不单独成单元 ---
            if (isHeading(trimmed)) {
                if (!current.isEmpty()) {
                    units.add(new Unit(current.toString().trim(), false, currentHeading));
                    current.setLength(0);
                }
                current.append(line).append('\n');
                if (currentHeading.isEmpty()) {
                    currentHeading = trimmed;
                }
                continue;
            }

            // --- 空行：普通块边界 ---
            if (trimmed.isEmpty()) {
                if (!current.isEmpty()) {
                    units.add(new Unit(current.toString().trim(), false, currentHeading));
                    current.setLength(0);
                    currentHeading = "";
                }
                continue;
            }

            current.append(line).append('\n');
        }

        // 收尾：未闭合的代码块（原文本身残缺）也要完整保留，不能丢
        if (codeBlock != null) {
            units.add(new Unit(trimLeadingTrailingBlank(codeBlock.toString()), true, currentHeading));
        }
        if (!current.isEmpty()) {
            units.add(new Unit(current.toString().trim(), false, currentHeading));
        }
        return units;
    }

    /** 是否为围栏行（``` 或 ~~~，可带语言标识）。 */
    private boolean isFence(String trimmed) {
        return trimmed.startsWith(FENCE) || trimmed.startsWith("~~~");
    }

    /** 围栏的重复字符数（```` 比 ``` 更长的围栏用于嵌套场景）。 */
    private int fenceLength(String trimmed) {
        char fenceChar = trimmed.charAt(0);
        int len = 0;
        while (len < trimmed.length() && trimmed.charAt(len) == fenceChar) {
            len++;
        }
        return len;
    }

    /**
     * 是否为闭合围栏：同一围栏字符、长度不小于开围栏、且围栏后无语言标识。
     * <p>
     * 这解决了「布尔翻转」的核心缺陷：块内出现 ``` 作为示例内容时，
     * 若其长度小于开围栏（比如块用 ```` 包裹），或被识别为带语言标识的内容，
     * 就不会被误判为闭合。
     */
    private boolean isClosingFence(String trimmed, int openLen) {
        if (trimmed.isEmpty()) {
            return false;
        }
        char fenceChar = trimmed.charAt(0);
        if (fenceChar != '`' && fenceChar != '~') {
            return false;
        }
        int len = fenceLength(trimmed);
        if (len < openLen) {
            return false;
        }
        // 闭合围栏后面不应再有语言标识等非空白内容
        return trimmed.substring(len).isBlank();
    }

    private boolean isHeading(String trimmed) {
        if (!trimmed.startsWith("#")) {
            return false;
        }
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '#') {
            i++;
        }
        return i >= 1 && i <= 6 && i < trimmed.length() && trimmed.charAt(i) == ' ';
    }

    private String trimLeadingTrailingBlank(String text) {
        return text.replaceAll("^\\s*\\n", "").replaceAll("\\n\\s*$", "");
    }

    // ------------------------------------------------------------------
    // 第二步：聚合为块（含滑动重叠）
    // ------------------------------------------------------------------

    private List<String> aggregate(List<Unit> units) {
        int maxChars = tokenEstimator.charsForTokens(MAX_TOKENS);
        int targetChars = tokenEstimator.charsForTokens(TARGET_TOKENS);
        int overlapChars = tokenEstimator.charsForTokens(OVERLAP_TOKENS);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String overlapPrefix = "";

        for (Unit unit : units) {
            if (unit.codeBlock()) {
                // 超长代码块自成一块（代码不可切，宁可单块超窗）
                if (unit.text().length() > targetChars) {
                    flush(chunks, current);
                    chunks.add(unit.text());
                    overlapPrefix = "";
                    continue;
                }
            }

            int projected = current.length() + unit.text().length() + 2;
            if (projected > maxChars && current.length() >= targetChars) {
                String finished = current.toString().trim();
                chunks.add(finished);
                // Overlap 只取普通段落尾部（跳过代码块，防残片）
                overlapPrefix = extractOverlapTail(finished, overlapChars);
                current.setLength(0);
                if (!overlapPrefix.isBlank()) {
                    current.append(overlapPrefix).append("\n\n");
                }
            }

            current.append(unit.text()).append("\n\n");
        }

        flush(chunks, current);
        return chunks;
    }

    private void flush(List<String> chunks, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isBlank()) {
            chunks.add(text);
        }
        current.setLength(0);
    }

    /**
     * 提取重叠区：从块尾部倒着取普通段落，遇到代码块即停止。
     * <p>
     * 直接按字符截尾会把代码块切出半截（下一块开头就成了无开围栏的残片），
     * 因此这里按「段落」为单位回取，且跳过任何代码块段落。
     */
    private String extractOverlapTail(String chunk, int overlapChars) {
        if (chunk.length() <= overlapChars) {
            return "";
        }
        String[] paragraphs = chunk.split("\\n\\s*\\n");
        StringBuilder tail = new StringBuilder();
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String para = paragraphs[i].trim();
            if (para.isEmpty()) {
                continue;
            }
            // 跳过代码块段落：以围栏开头的段落一律不进入重叠区
            if (para.startsWith(FENCE) || para.startsWith("~~~")) {
                break;
            }
            if (tail.length() + para.length() > overlapChars) {
                // 单个段落就超预算：只取该段落末尾的完整句子，不切碎句子
                String sentenceTail = tailOnSentenceBoundary(para, overlapChars - tail.length());
                if (!sentenceTail.isBlank()) {
                    tail.insert(0, sentenceTail + "\n\n");
                }
                break;
            }
            tail.insert(0, para + "\n\n");
        }
        return tail.toString().trim();
    }

    /** 在句子边界上取尾部，避免截断半句话。 */
    private String tailOnSentenceBoundary(String paragraph, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (paragraph.length() <= budget) {
            return paragraph;
        }
        String candidate = paragraph.substring(paragraph.length() - budget);
        int breakPoint = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                breakPoint = i;
                break;
            }
        }
        if (breakPoint >= 0 && breakPoint + 1 < candidate.length()) {
            return candidate.substring(breakPoint + 1).trim();
        }
        return candidate.trim();
    }

    // ------------------------------------------------------------------
    // 第三步：元数据挂载与片段 ID
    // ------------------------------------------------------------------

    private Map<String, Object> buildMetadata(DocMetadata metadata, String originUrl, int index, int total) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", metadata.title());
        meta.put("summary", metadata.summary());
        meta.put("tags", metadata.tags());
        meta.put("entities", metadata.entities());
        meta.put("url", originUrl == null ? "" : originUrl);
        meta.put("chunkIndex", index);
        meta.put("totalChunks", total);
        return meta;
    }

    /** 片段 ID：SHA256(docId_index) 前 16 位，长度可控且碰撞概率足够低。 */
    private String generateChunkId(String docId, int index) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((docId + "_" + index).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 8; i++) {
                String h = Integer.toHexString(0xff & hash[i]);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
}
