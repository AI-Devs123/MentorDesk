package com.nexusmind.rag.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import java.io.IOException;

/**
 * 代码感知分词器（切片 2）。
 *
 * <p>与 {@code CharTokenizer} 的「逐字符切分」不同，本分词器按<b>词性边界</b>切分：
 * <ul>
 *   <li>连续字母/数字构成一个标识符 token（camelCase、snake_case、数字均原样进入单 token）；</li>
 *   <li>连续符号字符构成一个<b>算子 token</b>（如 {@code ->}、{@code =>}、{@code ::}、{@code ==}，
 *       不会把 {@code ->} 拆成 {@code -} 与 {@code >}），从而保证「精准符号召回」；</li>
 *   <li>空白仅作分隔，不参与 token。</li>
 * </ul>
 *
 * <p>下游 {@link CodeSubwordFilter} 再负责把标识符 token 切成子词（getUser → get / user），
 * 而算子 token 保持原样。所有 token 经 {@link org.apache.lucene.analysis.LowerCaseFilter} 归一，
 * 使代码检索大小写不敏感。
 *
 * <p>实现为单次线性扫描（O(N)），无回溯、无可构造灾难性回溯的正则，绝无 ReDoS 风险。
 */
public final class CodeSymbolTokenizer extends Tokenizer {

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);

    private final StringBuilder buf = new StringBuilder();
    private int offset = 0;          // 已消费的绝对字符偏移
    private int tokenStart = 0;      // 当前 token 起始绝对偏移
    private int pushedBack = -1;     // 边界字符的回退缓冲（词↔符号过渡时未消费的那个字符）

    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();
        buf.setLength(0);
        boolean started = false;
        int c = nextChar();
        while (c != -1) {
            if (Character.isWhitespace(c)) {
                if (started) {
                    break; // 词内遇到空白 → 结束当前 token
                }
                offset++; // 计入绝对偏移，保证 offset 与字符流严格对齐
                c = nextChar();
                continue;
            }
            boolean wordChar = Character.isLetterOrDigit(c);
            if (started) {
                boolean prevWord = Character.isLetterOrDigit(buf.charAt(buf.length() - 1));
                if (prevWord != wordChar) {
                    // 词↔符号过渡：当前字符属于下一个 token，回退后结束本次
                    pushedBack = c;
                    break;
                }
            }
            if (!started) {
                tokenStart = offset;
                started = true;
            }
            buf.append((char) c);
            offset++;
            c = nextChar();
        }
        if (!started) {
            return false; // 流已耗尽且未读到任何字符
        }
        termAtt.setEmpty().append(buf);
        offsetAtt.setOffset(tokenStart, tokenStart + buf.length());
        return true;
    }

    private int nextChar() throws IOException {
        if (pushedBack != -1) {
            int v = pushedBack;
            pushedBack = -1;
            return v;
        }
        return input.read();
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        offset = 0;
        tokenStart = 0;
        pushedBack = -1;
        buf.setLength(0);
    }
}
