package com.nexusmind.agent.curator.chunker;

import com.nexusmind.agent.curator.model.CuratedDocumentChunk;
import com.nexusmind.agent.curator.model.DocMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自适应语义分块器测试。
 * <p>
 * 重点验证三件事（也是原稿实现会失败的地方）：
 * 1. 代码块<b>严格不腰斩</b>——用「围栏出现次数为偶数」的强断言，而非弱包含断言；
 * 2. 块内含 ``` 示例内容时<b>不被误判为闭合围栏</b>；
 * 3. Overlap <b>不从代码块取</b>，避免下一块以无开围栏的代码残片开头。
 */
class AdaptiveSemanticChunkerTest {

    private final AdaptiveSemanticChunker chunker = new AdaptiveSemanticChunker(new TokenEstimator());

    private static final DocMetadata META = new DocMetadata(
            "Dubbo 3 Triple 架构解析",
            "阐述 Triple 协议基于 HTTP/2 的流式设计与 gRPC 互通性。",
            List.of("Dubbo3", "HTTP2", "RPC"),
            List.of("Triple", "RST_STREAM", "Netty"));

    /** 统计片段中围栏标记的出现次数。 */
    private int fenceCount(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("```", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }

    @Test
    @DisplayName("元数据挂载完整，chunkIndex 连续且 totalChunks 一致")
    void shouldMountMetadata() {
        String markdown = """
                # Dubbo 3 Triple 协议

                Triple 协议是 Dubbo 3 主打的下一代通信协议。它天然兼容 gRPC 生态并拥抱标准 HTTP/2，
                通过在标准 HTTP/2 Frame 中封装数据实现双向流式推流与跨语言互通能力。

                ## 核心实现

                在微服务网关层面，Triple 协议不仅支持二进制 Protobuf 序列化，还天然支持 JSON 降级，
                这使得移动端穿透与网关解耦成为可能，同时也降低了异构系统接入的门槛。
                """;

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                "doc_test_1", markdown, META, "https://example.com/dubbo3");

        assertFalse(chunks.isEmpty(), "应产出至少一个片段");
        assertEquals(chunks.size(), chunks.get(0).totalChunks(), "totalChunks 应与实际数量一致");
        for (int i = 0; i < chunks.size(); i++) {
            CuratedDocumentChunk c = chunks.get(i);
            assertEquals(i, c.chunkIndex(), "chunkIndex 应连续递增");
            assertEquals("Dubbo 3 Triple 架构解析", c.metadata().get("title"));
            assertEquals("https://example.com/dubbo3", c.metadata().get("url"));
            assertTrue(c.chunkId().length() == 16, "chunkId 应为 16 位十六进制, 实际: " + c.chunkId());
            assertTrue(c.metadata().containsKey("tags"), "应挂载 tags");
            assertTrue(c.metadata().containsKey("entities"), "应挂载 entities");
        }
        // chunkId 不应重复
        assertNotEquals(chunks.get(0).chunkId(),
                chunks.size() > 1 ? chunks.get(1).chunkId() : "different");
    }

    @Test
    @DisplayName("代码块严格不腰斩：每个片段围栏次数必为偶数（强断言）")
    void shouldNeverSplitCodeBlock() {
        String markdown = """
                # 协议实现

                下面给出一个完整的流式服务端实现，注意它的回调结构与帧处理顺序，
                这是理解 Triple 双向流的关键所在，也是排查背压问题的起点。

                ```java
                public class TripleServerStream {
                    private final List<byte[]> buffer = new ArrayList<>();

                    public void onData(byte[] bytes) {
                        buffer.add(bytes);
                        processStreamFrame(bytes);
                    }

                    private void processStreamFrame(byte[] bytes) {
                        // 逐帧解析，注意这里的半包处理逻辑
                        FrameDecoder.decode(bytes).forEach(this::dispatch);
                    }
                }
                ```

                以上实现展示了帧的累积与分发，生产环境还需要处理超时与背压。
                当缓冲区持续增长时要及时触发流控，避免内存被打满导致 OOM，
                建议结合虚拟线程的载体释放策略一起考虑。
                """;

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                "doc_code", markdown, META, "https://example.com/code");

        for (CuratedDocumentChunk c : chunks) {
            int fences = fenceCount(c.textContent());
            assertEquals(0, fences % 2,
                    "片段中围栏必须成对出现（不被腰斩），实际围栏数=" + fences + "，片段内容:\n" + c.textContent());
        }

        // 代码块必须完整存在于某一个片段中
        boolean found = chunks.stream().anyMatch(c ->
                c.textContent().contains("public class TripleServerStream")
                        && c.textContent().contains("processStreamFrame"));
        assertTrue(found, "代码块应完整存在于单个片段内");
    }

    @Test
    @DisplayName("块内出现 ``` 示例内容时不得误判为闭合围栏（外层用更长围栏包裹）")
    void shouldNotMisinterpretInnerFenceAsClosing() {
        // 外层用 ```` (4 个反引号) 包裹，内层含 ``` 作为示例文本
        String markdown = """
                # Markdown 语法示例

                本文讲解如何在文档中展示代码围栏，下面这段用四个反引号包裹，
                内部包含三个反引号作为示例内容，用于教学演示。

                ````
                下面是嵌套示例：
                ```java
                int a = 1;
                ```
                以上是内层代码块。
                ````

                理解了嵌套规则后，就可以安全地在文档中教授 Markdown 语法了。
                """;

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                "doc_nested_fence", markdown, META, "https://example.com/fence");

        // 整个外层代码块应作为一个原子单元，不被内层 ``` 提前截断
        boolean hasFullNestedBlock = chunks.stream().anyMatch(c ->
                c.textContent().contains("下面是嵌套示例")
                        && c.textContent().contains("以上是内层代码块"));
        assertTrue(hasFullNestedBlock,
                "含内层围栏的代码块应保持完整，实际片段:\n"
                        + chunks.stream().map(CuratedDocumentChunk::textContent).reduce("", (a, b) -> a + "\n---\n" + b));
    }

    @Test
    @DisplayName("超长文档应切出多个片段，且 Overlap 不从代码块取")
    void shouldChunkLongDocumentWithoutCodeOverlap() {
        StringBuilder sb = new StringBuilder("# 长文档\n\n");
        for (int i = 0; i < 30; i++) {
            sb.append("这是第 ").append(i).append(" 段正文，用于把文档撑到需要多块的长度。")
              .append("每段都包含足够的自然语言内容，确保聚合窗口能被正常触发，从而验证分块与重叠逻辑的正确性。")
              .append("段落之间通过空行分隔，符合 Markdown 的语义边界定义。\n\n");
        }
        sb.append("```java\nclass Tail { void run() { /* 结尾代码块 */ } }\n```\n");

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                "doc_long", sb.toString(), META, "https://example.com/long");

        assertTrue(chunks.size() > 1, "长文档应被切成多块, 实际块数: " + chunks.size());

        // 除最后一块外，若某块以围栏片段开头则说明 Overlap 取到了代码残片
        for (int i = 1; i < chunks.size(); i++) {
            String head = chunks.get(i).textContent().trim();
            boolean startsWithDanglingFence = head.startsWith("```") || head.startsWith("~~~");
            if (startsWithDanglingFence) {
                // 允许的情况：它本身就是完整的代码块片段（围栏数为偶数）
                assertEquals(0, fenceCount(head) % 2,
                        "第 " + i + " 块以围栏开头且围栏未闭合，说明 Overlap 引入了代码残片");
            }
        }
    }

    @Test
    @DisplayName("空输入返回空列表，不抛异常")
    void shouldHandleEmptyInput() {
        assertTrue(chunker.chunkDocument("doc_x", "", META, "u").isEmpty());
        assertTrue(chunker.chunkDocument("doc_x", null, META, "u").isEmpty());
        assertTrue(chunker.chunkDocument("doc_x", "   \n\n  ", META, "u").isEmpty());
    }

    @Test
    @DisplayName("标题应作为正文前缀一起聚合，不单独成块")
    void shouldNotProduceHeadingOnlyChunk() {
        String markdown = """
                # 第一节

                第一节的正文内容，用于验证标题不会脱离正文单独成为一个片段，
                这是保证检索时语义完整的必要条件，否则会出现只有标题没有内容的空块。

                # 第二节

                第二节的正文内容同样需要与它的标题保持在同一个片段内，
                这样检索命中时才能同时拿到上下文标题与正文论述。
                """;

        List<CuratedDocumentChunk> chunks = chunker.chunkDocument(
                "doc_heading", markdown, META, "https://example.com/h");

        for (CuratedDocumentChunk c : chunks) {
            String t = c.textContent().trim();
            boolean isHeadingOnly = t.startsWith("#") && !t.contains("\n");
            assertFalse(isHeadingOnly, "不应出现只有标题的单独片段, 实际: " + t);
        }
    }
}
