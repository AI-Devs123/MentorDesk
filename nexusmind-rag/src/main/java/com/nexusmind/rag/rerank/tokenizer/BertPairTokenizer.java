package com.nexusmind.rag.rerank.tokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量级 BERT/RoBERTa WordPiece 句对分词器（零重型依赖，切片 4）。
 *
 * <p><b>为什么必须真 WordPiece</b>：Cross-Encoder 的语义相关性完全取决于 input_ids 是否
 * 与训练时一致。若像初版那样「整词查表、查不到即 [UNK]」，则代码标识符（camelCase / 点分路径）
 * 与无空格中文几乎全变成 [UNK]，BGE 拿到的输入约等于随机噪声 —— 精排等于没精排。
 * 这里实现标准 BERT 流程：CJK 单字预切分 + 拉丁串按 camelCase/分隔符切分 + 贪婪最长子词匹配
 * （续接子词带 {@code ##} 前缀），未登录字回落 [UNK]。
 *
 * <p>输入 vocab.txt：每行一个 token，行号即 token id（与 HF BertTokenizer 词表格式一致）。
 */
public class BertPairTokenizer {

    private static final Logger log = LoggerFactory.getLogger(BertPairTokenizer.class);

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int clsId;
    private final int sepId;
    private final int unkId;
    private final int padId;

    public BertPairTokenizer(InputStream vocabStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(vocabStream, StandardCharsets.UTF_8))) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    vocab.put(line, idx++);
                }
            }
        } catch (IOException e) {
            log.error("[BertPairTokenizer] 加载 vocab.txt 失败", e);
        }
        this.clsId = vocab.getOrDefault("[CLS]", 101);
        this.sepId = vocab.getOrDefault("[SEP]", 102);
        this.unkId = vocab.getOrDefault("[UNK]", 100);
        this.padId = vocab.getOrDefault("[PAD]", 0);
    }

    /** 句对编码后的整批张量（行优先，长度均为 batchSize * maxSeqLen）。 */
    public record TokenizedBatch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {
    }

    /**
     * 将 (Query, Doc) 句对拼接为单序列：[CLS] Q [SEP] D [SEP]，并生成三类输入张量。
     *
     * @param queries    长度 = batchSize
     * @param docs       长度 = batchSize
     * @param maxSeqLen  最大序列长度（含 [CLS]/[SEP]/[SEP] 共 3 个特殊位）
     */
    public TokenizedBatch encodePairs(List<String> queries, List<String> docs, int maxSeqLen) {
        int batchSize = queries.size();
        long[][] inputIds = new long[batchSize][maxSeqLen];
        long[][] attentionMask = new long[batchSize][maxSeqLen];
        long[][] tokenTypeIds = new long[batchSize][maxSeqLen];

        for (int i = 0; i < batchSize; i++) {
            List<Integer> qTokens = tokenize(queries.get(i));
            // Query 截断保护：最多保留 64 个 token，避免长 query 挤压 doc 预算
            if (qTokens.size() > 64) {
                qTokens = qTokens.subList(0, 64);
            }
            // doc 预算 = 总长 - [CLS]/[SEP]/[SEP]3 位 - query 实际占用
            int docBudget = maxSeqLen - 3 - qTokens.size();
            List<Integer> dTokens = tokenize(docs.get(i));
            if (dTokens.size() > docBudget) {
                dTokens = dTokens.subList(0, Math.max(0, docBudget));
            }

            int pos = 0;
            // [CLS]
            inputIds[i][pos] = clsId;
            attentionMask[i][pos] = 1;
            tokenTypeIds[i][pos] = 0;
            pos++;
            // Query 段（segment 0）
            for (int t : qTokens) {
                inputIds[i][pos] = t;
                attentionMask[i][pos] = 1;
                tokenTypeIds[i][pos] = 0;
                pos++;
            }
            // [SEP]
            inputIds[i][pos] = sepId;
            attentionMask[i][pos] = 1;
            tokenTypeIds[i][pos] = 0;
            pos++;
            // Doc 段（segment 1）
            for (int t : dTokens) {
                if (pos >= maxSeqLen) {
                    break; // 防御性保护，正常情况下不会触发
                }
                inputIds[i][pos] = t;
                attentionMask[i][pos] = 1;
                tokenTypeIds[i][pos] = 1;
                pos++;
            }
            // 末尾 [SEP]（segment 1）
            if (pos < maxSeqLen) {
                inputIds[i][pos] = sepId;
                attentionMask[i][pos] = 1;
                tokenTypeIds[i][pos] = 1;
                pos++;
            }
            // 其余位天然为 0（PAD），attentionMask/type 默认 0
        }
        return new TokenizedBatch(inputIds, attentionMask, tokenTypeIds);
    }

    /**
     * 单条文本 → token id 列表。
     *
     * <p>流程：
     * <ol>
     *   <li>预处理切分：CJK 单字成词；拉丁字母/数字流式成词（按 camelCase 与 . _ / - 切分）；
     *       标点与空白作为分隔/独立词。</li>
     *   <li>对每段做 WordPiece 贪婪最长匹配（续接子词带 {@code ##}）。</li>
     *   <li>未登录子串整体回落 [UNK]。</li>
     * </ol>
     */
    private List<Integer> tokenize(String text) {
        List<Integer> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String word : splitToWords(text)) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.length() == 1) {
                // 单字（含 CJK 单字 / 单标点）直接查表
                Integer id = vocab.get(word);
                tokens.add(id != null ? id : unkId);
                continue;
            }
            wordPiece(word.toLowerCase(Locale.ROOT), tokens);
        }
        return tokens;
    }

    /** 贪婪最长子词匹配，续接子词加 {@code ##} 前缀。 */
    private void wordPiece(String word, List<Integer> out) {
        int start = 0;
        int n = word.length();
        while (start < n) {
            int end = n;
            Integer id = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) {
                    sub = "##" + sub;
                }
                Integer cand = vocab.get(sub);
                if (cand != null) {
                    id = cand;
                    break;
                }
                end--;
            }
            if (id == null) {
                // 当前字符起无法拼出任何子词 → 整段未登录，逐字 UNK 更稳妥
                out.add(unkId);
                start++;
            } else {
                out.add(id);
                start = end;
            }
        }
    }

    /**
     * 预处理切分。
     *
     * <p>CJK 字符单字成词（BERT 中文词表以单字 token 为主，WordPiece 可在此之上续接）；
     * 拉丁字母数字先按 camelCase 与 {@code . _ / -} 切分为多个子词单元，再各自 WordPiece。
     */
    private List<String> splitToWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                flushLatin(latin, words);
            } else if (isCjk(cp)) {
                flushLatin(latin, words);
                words.add(new String(Character.toChars(cp))); // CJK 单字成词
            } else if (Character.isLetterOrDigit(cp)) {
                latin.appendCodePoint(cp);
            } else {
                // 标点 / 符号：隔离为独立词，并先 flush 前面的拉丁串
                flushLatin(latin, words);
                words.add(new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        flushLatin(latin, words);
        return words;
    }

    /**
     * 把累积的拉丁串按 camelCase / 分隔符切分后加入 words（保持原大小写，WordPiece 时统一小写）。
     */
    private void flushLatin(StringBuilder latin, List<String> words) {
        if (latin.length() == 0) {
            return;
        }
        String s = latin.toString();
        latin.setLength(0);
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '.' || c == '_' || c == '/' || c == '-' || c == '\\') {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                // camelCase 边界：小写字母后接大写字母
                if (cur.length() > 0 && Character.isUpperCase(c)
                        && Character.isLowerCase(cur.charAt(cur.length() - 1))) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            words.add(cur.toString());
        }
    }

    /** 判断是否为 CJK 统一表意文字（含扩展 A），覆盖中/日/韩主要区块。 */
    private boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF)   // 扩展 A
                || (cp >= 0x3040 && cp <= 0x30FF)   // 日文平假名/片假名
                || (cp >= 0xAC00 && cp <= 0xD7AF);  // 韩文音节
    }

    public int unkId() {
        return unkId;
    }
}
