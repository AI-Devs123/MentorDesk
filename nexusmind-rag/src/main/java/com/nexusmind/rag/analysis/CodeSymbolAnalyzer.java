package com.nexusmind.rag.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

/**
 * 代码感知分析器（切片 2）。
 *
 * <p>链路：{@link CodeSymbolTokenizer}（词性边界切分 + 算子整体保留）
 * → {@link CodeSubwordFilter}（标识符子词拆解）→ {@link LowerCaseFilter}（大小写归一）。
 *
 * <p>专用于 {@code code} / {@code web_kb} 类集合；中文日记类集合仍由
 * {@link org.apache.lucene.analysis.standard.StandardAnalyzer} 承载，
 * 由 {@code LuceneDualEngineManager#analyzerFor(String)} 按集合名分发，避免「全局替换分析器」误伤中文分词。
 */
public final class CodeSymbolAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer src = new CodeSymbolTokenizer();
        TokenStream tok = new CodeSubwordFilter(src);
        tok = new LowerCaseFilter(tok);
        return new TokenStreamComponents(src, tok);
    }
}
