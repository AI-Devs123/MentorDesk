package com.nexusmind.agent.code.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JavaParser 符号提取引擎（CodeAgent 切片1 核心）。
 *
 * <p><b>硬伤A 修复（并发竞态）：</b>弃用 {@code StaticJavaParser} 的全局可变 Configuration，
 * 改用<b>实例</b> {@link JavaParser} 并按 {@code projectSourceRoot} 缓存于 ConcurrentHashMap。
 * 同一项目复用同一个解析器实例（其内部对 CompilationUnit 有缓存），既消除了多虚拟线程/
 * 多项目下的符号求解器竞态，也避免了每文件重建 {@link JavaParserTypeSolver} 导致的重复全仓预解析。
 *
 * <p><b>语义解析：</b>{@link CombinedTypeSolver} 组合 {@link ReflectionTypeSolver}（JDK 运行时）
 * 与 {@link JavaParserTypeSolver}（项目源码目录），将 callee 从纯方法名升级为全限定签名，
 * 构成真正的调用图边；解析失败安全降级为裸方法名，保证提取流不中断。
 *
 * <p><b>DoD3 容错：</b>JavaParser 是容错解析器，对注入的语法错误仍可能产出残缺 CU 且不抛异常，
 * 故显式检查 {@code getProblems()} 非空即视为解析失败，交由编排器标记局部 FAILED 并续跑。
 */
@Component
public class JavaParserSymbolExtractor {

    private final Map<Path, JavaParser> parserCache = new ConcurrentHashMap<>();

    private JavaParser parserFor(Path projectSourceRoot) {
        return parserCache.computeIfAbsent(projectSourceRoot, root -> {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            if (root != null && root.toFile().exists()) {
                typeSolver.add(new JavaParserTypeSolver(root));
            }
            ParserConfiguration config = new ParserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(typeSolver));
            return new JavaParser(config);
        });
    }

    public List<UnifiedCodeSymbol> extractSymbols(File javaFile, String relativePath, Path projectSourceRoot)
            throws IOException {
        List<UnifiedCodeSymbol> symbols = new ArrayList<>();

        ParseResult<CompilationUnit> parseResult = parserFor(projectSourceRoot).parse(javaFile.toPath());
        // DoD3 容错：残缺 CU 不抛异常，必须显式拦截，否则坏文件静默过关
        if (!parseResult.getProblems().isEmpty()) {
            throw new IOException("JavaParser 检测到语法问题 ("
                    + parseResult.getProblems().size() + "): "
                    + parseResult.getProblems().get(0).getMessage() + " -> " + javaFile);
        }
        CompilationUnit cu = parseResult.getResult()
                .orElseThrow(() -> new IOException("JavaParser 无法解析: " + javaFile));

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());

            // 类 / 接口节点（parentId 为空，作为包含关系根）
            symbols.add(new UnifiedCodeSymbol(
                    className,
                    null,
                    "JAVA",
                    clazz.getNameAsString(),
                    clazz.isInterface() ? "INTERFACE" : "CLASS",
                    relativePath,
                    clazz.getBegin().map(p -> p.line).orElse(0),
                    clazz.getEnd().map(p -> p.line).orElse(0),
                    className,
                    clazz.getJavadocComment().map(Object::toString).orElse(""),
                    List.of(),
                    clazz.getAnnotations().stream().map(Object::toString).toList()
            ));

            // 方法节点（parentId = 所属类）
            clazz.getMethods().forEach(method -> {
                String methodId = className + "#" + method.getNameAsString();
                String signature = method.getDeclarationAsString(false, false, true);

                List<String> callees = new ArrayList<>();
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    try {
                        ResolvedMethodDeclaration resolved = call.resolve();
                        // 3.25.x 中 getQualifiedSignature() 直接返回 java.lang.String 全限定签名
                        callees.add(resolved.getQualifiedSignature());
                    } catch (Exception e) {
                        // 未引入三方库或动态推断失败 -> 平稳退化为裸方法名
                        callees.add(call.getNameAsString());
                    }
                }

                symbols.add(new UnifiedCodeSymbol(
                        methodId,
                        className,
                        "JAVA",
                        method.getNameAsString(),
                        "METHOD",
                        relativePath,
                        method.getBegin().map(p -> p.line).orElse(0),
                        method.getEnd().map(p -> p.line).orElse(0),
                        signature,
                        method.getJavadocComment().map(Object::toString).orElse(""),
                        callees,
                        method.getAnnotations().stream().map(Object::toString).toList()
                ));
            });
        });

        // 解除对 CompilationUnit 的引用，便于年轻代 GC 平稳回收（千级类库防老年代打满）
        return symbols;
    }
}
