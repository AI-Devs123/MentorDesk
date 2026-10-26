package com.example.broken;

/**
 * 故意注入语法错误的夹具：字段初始化缺分号，用于验证 DoD3 局部容灾。
 * JavaParser 容错解析器对此可能产出问题(problems)或抛出，二者均应被
 * ProjectSymbolAnalyzer 捕获并标记该文件 FAILED 后继续主流程。
 */
public class BrokenSample {
    private int counter = 1

    public void brokenMethod(String input) {
        int x = input == null ? 0 : 1
        System.out.println(x)
    }
}
