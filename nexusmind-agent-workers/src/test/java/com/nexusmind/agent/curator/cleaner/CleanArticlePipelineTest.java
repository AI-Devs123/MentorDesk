package com.nexusmind.agent.curator.cleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 2 提纯流水线测试。
 * <p>
 * 覆盖：广告/页脚剔除、Base64 阻断、tracking 参数清洗、代码块语言标识、
 * 嵌套列表、嵌套表格、相对链接补全、javascript: 毒链接拦截、正文过短门禁。
 */
class CleanArticlePipelineTest {

    private final CleanArticlePipeline pipeline = new CleanArticlePipeline(
            new JsoupPreCleaner(),
            new ReadabilityExtractor(),
            new HtmlToMarkdownConverter(new UrlSanitizer())
    );

    private static final String BASE = "https://example.com/dubbo3";

    // ---------------------------------------------------------------------
    // 主干用例：对应切片 2 的 DoD
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("去噪 + Base64 阻断 + tracking 清洗 + 代码块保留")
    void shouldExtractCleanArticle() {
        String html = """
                <html>
                <head>
                  <title>Dubbo 3 Triple 协议深度剖析 - NexusMind</title>
                  <meta name="author" content="张工">
                  <meta property="article:published_time" content="2026-10-20T10:00:00+08:00">
                  <meta name="description" content="Triple 协议与 gRPC 对齐的实现细节">
                  <link rel="canonical" href="https://example.com/dubbo3">
                </head>
                <body>
                  <header><nav>Home | About | Contact</nav></header>
                  <div class="sidebar ad-box">
                    <p>购买云服务器优惠大促销，限时五折，点击立即抢购领取新人礼包！</p>
                  </div>
                  <div id="main-content" class="article-content">
                    <h1>Dubbo 3 Triple 协议深度剖析</h1>
                    <p>Triple 协议是 Dubbo 3 主推的主力通信协议，它完全对齐 gRPC 标准并原生运行在 HTTP/2 之上，支持双向流式调用。</p>
                    <p>它支持双向流式推流（Bi-directional Streaming），同时兼容移动端穿透与网关解耦，在长连接场景下表现稳定。</p>
                    <pre><code class="language-protobuf">service AgentStreamService {\n  rpc Stream(Task) returns (Progress);\n}</code></pre>
                    <img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA..." alt="架构图"/>
                    <p>更多细节请参考 <a href="https://dubbo.apache.org/docs/?utm_source=spring_community&amp;spm=100.1">官方文档</a>，也可以查看源码实现部分。</p>
                  </div>
                  <div class="footer-container">
                    <p>Copyright 2026 NexusMind All rights reserved. 未经许可禁止转载本站任何内容。</p>
                  </div>
                </body>
                </html>
                """;

        CleanArticlePipeline.CleanMarkdownDocument result = pipeline.execute(html, BASE);
        String md = result.markdown();

        // 标题：剥掉 " - NexusMind" 站点后缀
        assertEquals("Dubbo 3 Triple 协议深度剖析", result.title());

        // 正文保留
        assertTrue(md.contains("Triple 协议是 Dubbo 3 主推的主力通信协议"), "应保留正文首段，实际:\n" + md);
        assertTrue(md.contains("双向流式推流"), "应保留正文次段，实际:\n" + md);

        // 代码块保留 + 语言标识
        assertTrue(md.contains("```protobuf"), "代码块应带语言标识，实际:\n" + md);
        assertTrue(md.contains("service AgentStreamService"), "应保留代码内容，实际:\n" + md);

        // 去噪：广告与页脚被干掉
        assertFalse(md.contains("购买云服务器优惠大促销"), "广告应被剔除，实际:\n" + md);
        assertFalse(md.contains("Copyright 2026"), "页脚应被剔除，实际:\n" + md);
        assertFalse(md.contains("Home | About | Contact"), "导航应被剔除，实际:\n" + md);

        // Base64 阻断
        assertFalse(md.contains("data:image/png;base64"), "Base64 应被阻断，实际:\n" + md);
        assertTrue(md.contains(HtmlToMarkdownConverter.EMBEDDED_IMAGE_PLACEHOLDER), "应输出占位符，实际:\n" + md);

        // tracking 参数清洗（保留域名与路径，去掉 utm_source / spm）
        assertFalse(md.contains("utm_source"), "utm_source 应被清洗，实际:\n" + md);
        assertFalse(md.contains("spm="), "spm 应被清洗，实际:\n" + md);
        assertTrue(md.contains("https://dubbo.apache.org/docs"), "应保留干净链接，实际:\n" + md);

        // 元数据抢在删除前抽取成功
        assertEquals("张工", result.meta().author());
        assertEquals("https://example.com/dubbo3", result.meta().canonical());
        assertTrue(result.meta().publishedAt().startsWith("2026-10-20"));
    }

    // ---------------------------------------------------------------------
    // 补强用例：初版实现会失败的场景
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("嵌套列表：多级缩进与列表项内行内格式均保留")
    void shouldPreserveNestedList() {
        String html = """
                <html><body>
                  <article>
                    <p>下面是本文的核心提纲，包含多个层级的知识点，建议按顺序阅读并动手验证每一个结论的正确性。</p>
                    <ul>
                      <li>通信层
                        <ul>
                          <li><strong>Triple</strong> 协议</li>
                          <li><code>RST_STREAM</code> 帧</li>
                        </ul>
                      </li>
                      <li>治理层</li>
                    </ul>
                    <p>以上提纲覆盖了从底层帧结构到上层治理的完整链路，理解后即可独立排障并定位协议层面的异常行为。</p>
                  </article>
                </body></html>
                """;

        String md = pipeline.execute(html, BASE).markdown();

        assertTrue(md.contains("- 通信层"), "一级列表项应保留，实际:\n" + md);
        assertTrue(md.contains("  - **Triple** 协议"), "二级列表应缩进且保留加粗，实际:\n" + md);
        assertTrue(md.contains("  - `RST_STREAM` 帧"), "二级列表应保留行内代码，实际:\n" + md);
        assertTrue(md.contains("- 治理层"), "同级列表项应保留，实际:\n" + md);
    }

    @Test
    @DisplayName("嵌套表格：内层表格的行不得混入外层列")
    void shouldNotFlattenNestedTable() {
        String html = """
                <html><body>
                  <article>
                    <p>下表对比了两种协议在多种维度上的差异，其中部分单元格内又嵌套了一张明细表格用于说明细节参数。</p>
                    <table>
                      <tr><th>协议</th><th>传输层</th></tr>
                      <tr>
                        <td>Triple</td>
                        <td>
                          <table>
                            <tr><th>帧</th><th>说明</th></tr>
                            <tr><td>HEADERS</td><td>元数据</td></tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                    <p>从上表可以看出，选择协议时需要同时考虑传输层能力与生态兼容性，而不是只看单一指标的数值高低。</p>
                  </article>
                </body></html>
                """;

        String md = pipeline.execute(html, BASE).markdown();

        // 外层表头正确
        assertTrue(md.contains("| 协议 | 传输层 |"), "外层表头应正确，实际:\n" + md);
        // 内层表头不得成为外层表头（初版 select("tr") 会把 HEADERS 行当成外层表头）
        assertFalse(md.startsWith("| 帧 | 说明 |"), "内层表格不应被当成外层表头，实际:\n" + md);
        assertTrue(md.contains("| Triple |"), "外层数据行应保留，实际:\n" + md);
    }

    @Test
    @DisplayName("相对链接按 baseUri 补全为绝对地址")
    void shouldResolveRelativeLink() {
        String html = """
                <html><body>
                  <article>
                    <p>本节内容较长，涉及协议帧结构与连接生命周期的完整说明，建议对照官方规范逐条阅读并做实验验证。</p>
                    <p>延伸阅读请见 <a href="/docs/advanced">进阶文档</a> 以及 <a href="../guide">上手指南</a>，其中包含大量可运行示例。</p>
                    <p>另外还可以参考站内 <a href="#section-2">第二节</a> 的推导过程，这部分对理解流式取消机制尤为关键。</p>
                  </article>
                </body></html>
                """;

        String md = pipeline.execute(html, BASE).markdown();

        assertTrue(md.contains("(/docs/advanced)") || md.contains("(https://example.com/docs/advanced)"),
                "相对链接应被补全，实际:\n" + md);
        assertTrue(md.contains("#section-2"), "fragment 锚点必须保留，实际:\n" + md);
        assertFalse(md.contains("null://"), "不得产出 null 协议链接，实际:\n" + md);
    }

    @Test
    @DisplayName("javascript: 毒链接降级为纯文本，不得写入 Markdown")
    void shouldBlockJavascriptProtocol() {
        String html = """
                <html><body>
                  <article>
                    <p>这篇文章讨论前端安全中的常见注入面，重点分析链接协议校验缺失导致的脚本执行风险与修复方案。</p>
                    <p>点击 <a href="javascript:alert(1)">这里</a> 查看演示，注意这种写法在归档后仍然危险，必须阻断。</p>
                    <p>与之类似的还有 <a href="java&#9;script:alert(2)">混淆写法</a>，攻击者常用制表符绕过简单的字符串匹配检查。</p>
                  </article>
                </body></html>
                """;

        String md = pipeline.execute(html, BASE).markdown();

        assertFalse(md.toLowerCase().contains("javascript:"), "javascript: 协议必须被阻断，实际:\n" + md);
        assertTrue(md.contains("这里"), "锚文本应保留为纯文本，实际:\n" + md);
    }

    @Test
    @DisplayName("正文过短（反爬页 / 登录墙）应抛异常而非静默产出空文档")
    void shouldRejectTooShortContent() {
        String html = "<html><body><div>请先登录后再查看全文</div></body></html>";

        CleanArticlePipeline.ContentExtractionException ex = assertThrows(
                CleanArticlePipeline.ContentExtractionException.class,
                () -> pipeline.execute(html, BASE));
        assertTrue(ex.getMessage().contains("正文提纯失败"), "应给出可识别的失败原因，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("on* 事件属性与内联脚本应被剥离，不影响正文转译")
    void shouldStripEventHandlerAttributes() {
        String html = """
                <html><body>
                  <article>
                    <p onclick="doEvil()">本文介绍事件属性注入的风险与防护手段，重点说明在归档管道中为何仍然必须剥离内联事件句柄，而不能指望下游渲染层来兜底。</p>
                    <p onmouseover="steal()">即使最终输出的是 Markdown，保留这些属性也可能在二次渲染时被利用，形成从归档文档到浏览器执行的完整攻击链，因此必须在入库前彻底清除。</p>
                    <p>此外还应同时清理 formaction、xlink:href 等少见但同样危险的位置，避免攻击者通过冷门属性绕过单一的 href 校验逻辑。</p>
                  </article>
                </body></html>
                """;

        String md = pipeline.execute(html, BASE).markdown();

        assertFalse(md.contains("onclick"), "onclick 应被剥离，实际:\n" + md);
        assertFalse(md.contains("onmouseover"), "onmouseover 应被剥离，实际:\n" + md);
        assertTrue(md.contains("事件属性注入"), "正文应正常输出，实际:\n" + md);
    }
}
