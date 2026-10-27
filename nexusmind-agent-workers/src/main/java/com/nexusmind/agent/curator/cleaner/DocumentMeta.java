package com.nexusmind.agent.curator.cleaner;

/**
 * 文档级基础元数据（切片 3 DocMetadataExtractor 的廉价先验）。
 * <p>
 * 在「预清洗删除 meta/link 等噪音节点」之前抢先抓取，避免丢失这些免费且高置信的信息：
 * 标题、作者、发布时间、描述、canonical 链接、站点名。
 *
 * @param title       文档标题（优先 og:title，回退 &lt;title&gt;，前缀站点名已剥离）
 * @param siteName    站点名（og:site_name），缺失时为空串
 * @param author      作者（meta author / article:author），缺失时为空串
 * @param publishedAt 发布时间原文（article:published_time 等），缺失时为空串
 * @param description 摘要描述（og:description / meta description），缺失时为空串
 * @param canonical   规范链接（link rel=canonical 的绝对地址），缺失时为空串
 */
public record DocumentMeta(
        String title,
        String siteName,
        String author,
        String publishedAt,
        String description,
        String canonical
) {
    /** 空元数据兜底，避免调用方到处判 null。 */
    public static DocumentMeta empty() {
        return new DocumentMeta("", "", "", "", "", "");
    }
}
