package com.nexusmind.rag.analysis;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 子词切分过滤器（切片 2）。
 *
 * <p>把标识符 token 拆成可独立命中的子词，使 {@code getUser} 既能整词命中，
 * 也能被 {@code get} / {@code user} 命中（语义更宽、召回更稳）。拆解规则：
 * <ul>
 *   <li>下划线 / 连字符 {@code _ -} 作为硬分隔；</li>
 *   <li>小驼峰与大驼峰边界（lower→Upper）；</li>
 *   <li>连续大写后接小写（缩写词结尾，如 {@code HTTPServer} → HTTP / Server）；</li>
 *   <li>字母 ↔ 数字过渡（{@code foo2} → foo / 2）。</li>
 * </ul>
 *
 * <p>算子 token（如 {@code ->}）不含字母数字，不会产生子词，原样透传。
 * 每个原始 token 与其子词都会被产出（原始 token 在前），以保证整词与子词双重召回。
 */
public final class CodeSubwordFilter extends TokenFilter {

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posAtt = addAttribute(PositionIncrementAttribute.class);

    private final Deque<String> pending = new ArrayDeque<>();
    // 预留扩展位：未来可注入停用子词集合（如语言关键字 a/an/the 的子词），当前未启用
    @SuppressWarnings("unused")
    private final CharArraySet stopSubwords;

    public CodeSubwordFilter(TokenStream input) {
        this(input, null);
    }

    public CodeSubwordFilter(TokenStream input, CharArraySet stopSubwords) {
        super(input);
        this.stopSubwords = stopSubwords;
    }

    @Override
    public boolean incrementToken() throws IOException {
        // 先吐出上一 token 余下的子词
        if (!pending.isEmpty()) {
            String s = pending.poll();
            termAtt.setEmpty().append(s);
            posAtt.setPositionIncrement(1);
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        String token = termAtt.toString();
        List<String> subs = splitSubwords(token);
        pending.add(token);
        for (String s : subs) {
            if (!s.equals(token) && !pending.contains(s)) {
                pending.add(s);
            }
        }
        String first = pending.poll();
        termAtt.setEmpty().append(first);
        posAtt.setPositionIncrement(1);
        return true;
    }

    private List<String> splitSubwords(String token) {
        List<String> subs = new ArrayList<>();
        if (token == null || token.isEmpty()) {
            return subs;
        }
        StringBuilder cur = new StringBuilder();
        int n = token.length();
        for (int i = 0; i < n; i++) {
            char c = token.charAt(i);
            if (c == '_' || c == '-') {
                flush(cur, subs);
                continue;
            }
            boolean curUpper = cur.length() > 0 && Character.isUpperCase(cur.charAt(cur.length() - 1));
            boolean curLower = cur.length() > 0 && Character.isLowerCase(cur.charAt(cur.length() - 1));
            boolean curDigit = cur.length() > 0 && Character.isDigit(cur.charAt(cur.length() - 1));
            boolean cUpper = Character.isUpperCase(c);
            boolean cLower = Character.isLowerCase(c);
            boolean cDigit = Character.isDigit(c);

            boolean boundary = false;
            if (cUpper && (curLower || (curUpper && i + 1 < n && Character.isLowerCase(token.charAt(i + 1))))) {
                boundary = true; // lower→Upper，或 缩写结尾(UpperUpper→lower)
            } else if ((cDigit && (curLower || curUpper)) || (curDigit && (cLower || cUpper))) {
                boundary = true; // 字母 ↔ 数字过渡
            }
            if (boundary) {
                flush(cur, subs);
            }
            cur.append(c);
        }
        flush(cur, subs);
        return subs;
    }

    private void flush(StringBuilder cur, List<String> subs) {
        if (cur.length() > 0) {
            subs.add(cur.toString());
            cur.setLength(0);
        }
    }
}
