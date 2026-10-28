package com.nexusmind.rag.rerank.tokenizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BertPairTokenizer 纯算法验证（不依赖真实 300MB 模型）。
 *
 * <p>用一份极小的测试词表（classpath:/vocab_test.txt）对拍 WordPiece 行为：
 * 拉丁整词命中、camelCase 切分、CJK 单字预切分、续接子词 ## 拼接、未登录回落 UNK。
 * 这正是切片 4 评审 P0-4 的核心修正 —— 初版整词查表会让代码/中文整段 [UNK]，精排失效。
 */
class BertPairTokenizerTest {

    private BertPairTokenizer load() {
        InputStream is = BertPairTokenizerTest.class.getResourceAsStream("/vocab_test.txt");
        assertNotNull(is, "测试词表应位于 classpath 根 (/vocab_test.txt)");
        return new BertPairTokenizer(is);
    }

    @Test
    @DisplayName("拉丁整词可直接命中，句对结构 [CLS] Q [SEP] D [SEP] 正确")
    void wholeWordAndPairStructure() {
        BertPairTokenizer t = load();
        // query=the(在词表), doc=world
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of("world"), 16);

        assertEquals(2, batch.inputIds()[0][0]);  // [CLS]
        assertEquals(11, batch.inputIds()[0][1]); // the（query 段）
        assertEquals(3, batch.inputIds()[0][2]);  // [SEP]
        assertEquals(5, batch.inputIds()[0][3]);  // world（doc 段）
        assertEquals(3, batch.inputIds()[0][4]);  // 末尾 [SEP]

        assertEquals(1, batch.attentionMask()[0][3]); // world 被 attend
        assertEquals(0, batch.tokenTypeIds()[0][1]);   // query 段 type=0
        assertEquals(1, batch.tokenTypeIds()[0][3]);   // doc 段 type=1
    }

    @Test
    @DisplayName("camelCase 标识符被切分并能 WordPiece 命中（不再整段 UNK）")
    void camelCaseSplit() {
        BertPairTokenizer t = load();
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of("startServer"), 16);

        assertEquals(6, batch.inputIds()[0][3]);  // start
        assertEquals(16, batch.inputIds()[0][4]); // server
    }

    @Test
    @DisplayName("CJK 单字预切分（中文不再整句 UNK）")
    void cjkSingleChar() {
        BertPairTokenizer t = load();
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of("中文"), 16);

        assertEquals(12, batch.inputIds()[0][3]); // 中
        assertEquals(14, batch.inputIds()[0][4]); // 文
    }

    @Test
    @DisplayName("未登录串回落 UNK")
    void unknownFallsToUnk() {
        BertPairTokenizer t = load();
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of("zzzqqq"), 16);

        assertEquals(t.unkId(), batch.inputIds()[0][3]);
    }

    @Test
    @DisplayName("续接子词 ## 拼接：servers = server + ##s")
    void continuationSubword() {
        BertPairTokenizer t = load();
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of("servers"), 16);

        assertEquals(16, batch.inputIds()[0][3]); // server
        assertEquals(8, batch.inputIds()[0][4]);  // ##s
    }

    @Test
    @DisplayName("超长 doc 按 maxSeqLen 预算截断，不越界")
    void docTruncationWithinBudget() {
        BertPairTokenizer t = load();
        String longDoc = "hello world start server the 中文 未知词 zzzqqq";
        BertPairTokenizer.TokenizedBatch batch =
                t.encodePairs(List.of("the"), List.of(longDoc), 8);

        // 总长 8：[CLS] the [SEP] ... doc ... [SEP]，doc 预算 = 8-3-1 = 4
        int lastAttended = -1;
        for (int p = 0; p < 8; p++) {
            if (batch.attentionMask()[0][p] == 1) {
                lastAttended = p;
            }
        }
        assertEquals(7, lastAttended); // 末尾 [SEP] 落在索引 7，未越界 8
    }
}
