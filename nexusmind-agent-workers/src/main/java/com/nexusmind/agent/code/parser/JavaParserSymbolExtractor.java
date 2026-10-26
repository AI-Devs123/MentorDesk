package com.nexusmind.agent.code.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.nexusmind.api.dto.UnifiedCodeSymbol;
import com.nexusmind.api.dto.UnifiedCodeSymbol.MethodParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JavaParser 符号提取引擎（CodeAgent 切片2：高保真符号表深度实现）。
 *
 * <p><b>并发安全（硬伤A 保持）：</b>沿用切片1 的<b>实例</b> {@link JavaParser} + 按
 * {@code projectSourceRoot} 缓存于 {@link ConcurrentHashMap}，彻底规避
 * {@code StaticJavaParser} 全局可变 Configuration 的多虚拟线程竞态，并复用 CompilationUnit 缓存。
 *
 * <p><b>切片2 升级：</b>遍历由 {@code ClassOrInterfaceDeclaration} 升级为
 * {@code TypeDeclaration} 全量穿透（类 / 接口 / 枚举 / Record / 内部 / 嵌套类），并补齐
 * 字段（FIELD）、枚举常量、继承关系（FQN）、修饰符、返回类型、参数签名、Javadoc 清洗与注解列表，
 * 为切片7 的 RAG 精准检索与 Call Graph 拓扑提供结构化语义。
 *
 * <p><b>鲁棒降级：</b>符号求解（callee 重载区分、superTypes FQN）失败一律安全退化为
 * 裸方法名 / 原名，保证提取流不中断，图拓扑不破。
 *
 * <p><b>DoD3 容错：</b>JavaParser 容错解析对语法错误仍可能产出残缺 CU 且不抛异常，故显式检查
 * {@code getProblems()} 非空即视为解析失败，交由编排器标记局部 FAILED 并续跑。
 */
@Component
public class JavaParserSymbolExtractor {

    private static final Logger log = LoggerFactory.getLogger(JavaParserSymbolExtractor.class);

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

    public List<UnifiedCodeSymbol> extractSymbols(Path javaFilePath, String relativePath, Path projectSourceRoot)
            throws IOException {
        List<UnifiedCodeSymbol> symbols = new ArrayList<>();

        // DoD3 容错：残缺 CU 不抛异常，必须显式拦截，否则坏文件静默过关
        ParseResult<CompilationUnit> parseResult = parserFor(projectSourceRoot).parse(javaFilePath);
        if (!parseResult.getProblems().isEmpty()) {
            throw new IOException("JavaParser 检测到语法问题 ("
                    + parseResult.getProblems().size() + "): "
                    + parseResult.getProblems().get(0).getMessage() + " -> " + javaFilePath);
        }
        CompilationUnit cu = parseResult.getResult()
                .orElseThrow(() -> new IOException("JavaParser 无法解析: " + javaFilePath));

        // 全量穿透：类 / 接口 / 枚举 / Record / 内部 / 嵌套类
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            processTypeDeclaration(type, relativePath, symbols);
        }
        return symbols;
    }

    private void processTypeDeclaration(TypeDeclaration<?> type, String relativePath, List<UnifiedCodeSymbol> sink) {
        String fqName = type.getFullyQualifiedName().orElse(type.getNameAsString());
        String parentId = type.getParentNode()
                .filter(p -> p instanceof TypeDeclaration)
                .flatMap(p -> ((TypeDeclaration<?>) p).getFullyQualifiedName())
                .orElse(null);

        String kind = resolveTypeKind(type);
        List<String> superTypes = extractSuperTypes(type);
        List<String> modifiers = type.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
        List<String> annotations = extractAnnotations(type);

        // 1. 注册类 / 接口 / 枚举 / Record 符号（类型符号的 signature 用全限定名作可读声明）
        sink.add(new UnifiedCodeSymbol(
                fqName,
                parentId,
                "JAVA",
                type.getNameAsString(),
                kind,
                relativePath,
                type.getBegin().map(p -> p.line).orElse(0),
                type.getEnd().map(p -> p.line).orElse(0),
                modifiers,
                null,
                List.of(),
                superTypes,
                fqName,
                type.getJavadocComment().map(this::cleanJavadoc).orElse(""),
                List.of(),
                annotations
        ));

        // 2. 字段提取（类 / 接口常量 / Record 组件由构造器参数体现，此处不重复）
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            for (FieldDeclaration field : cid.getFields()) {
                processFieldDeclaration(fqName, field, relativePath, sink);
            }
        } else if (type instanceof EnumDeclaration ed) {
            for (EnumConstantDeclaration constant : ed.getEntries()) {
                sink.add(new UnifiedCodeSymbol(
                        fqName + "." + constant.getNameAsString(),
                        fqName,
                        "JAVA",
                        constant.getNameAsString(),
                        "FIELD",
                        relativePath,
                        constant.getBegin().map(p -> p.line).orElse(0),
                        constant.getEnd().map(p -> p.line).orElse(0),
                        List.of(),
                        fqName,
                        List.of(),
                        List.of(),
                        fqName + "." + constant.getNameAsString(),
                        "",
                        List.of(),
                        extractAnnotations(constant)
                ));
            }
        }

        // 3. 成员方法与构造函数（含嵌套类型自身的成员）
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration method) {
                processMethodDeclaration(fqName, method, relativePath, sink);
            } else if (member instanceof ConstructorDeclaration constructor) {
                processConstructorDeclaration(fqName, constructor, relativePath, sink);
            }
        }
    }

    private void processFieldDeclaration(String classSymbolId, FieldDeclaration field, String relativePath, List<UnifiedCodeSymbol> sink) {
        List<String> modifiers = field.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
        List<String> annotations = extractAnnotations(field);
        for (com.github.javaparser.ast.body.VariableDeclarator var : field.getVariables()) {
            String fieldId = classSymbolId + "." + var.getNameAsString();
            sink.add(new UnifiedCodeSymbol(
                    fieldId,
                    classSymbolId,
                    "JAVA",
                    var.getNameAsString(),
                    "FIELD",
                    relativePath,
                    var.getBegin().map(p -> p.line).orElse(0),
                    var.getEnd().map(p -> p.line).orElse(0),
                    modifiers,
                    field.getElementType().asString(),
                    List.of(),
                    List.of(),
                    field.getElementType().asString() + " " + var.getNameAsString(),
                    field.getJavadocComment().map(this::cleanJavadoc).orElse(""),
                    List.of(),
                    annotations
            ));
        }
    }

    private void processMethodDeclaration(String classSymbolId, MethodDeclaration method, String relativePath, List<UnifiedCodeSymbol> sink) {
        List<MethodParam> parameters = method.getParameters().stream()
                .map(p -> new MethodParam(p.getNameAsString(), p.getTypeAsString()))
                .toList();
        String paramSignature = parameters.stream().map(MethodParam::type).collect(Collectors.joining(","));
        // 标识符形如: com.nexusmind.OrderService#checkout(java.lang.Long) —— 仅类型区分重载
        String methodId = String.format("%s#%s(%s)", classSymbolId, method.getNameAsString(), paramSignature);

        List<String> modifiers = method.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
        List<String> annotations = extractAnnotations(method);
        List<String> callees = extractCallees(method);

        sink.add(new UnifiedCodeSymbol(
                methodId,
                classSymbolId,
                "JAVA",
                method.getNameAsString(),
                "METHOD",
                relativePath,
                method.getBegin().map(p -> p.line).orElse(0),
                method.getEnd().map(p -> p.line).orElse(0),
                modifiers,
                method.getTypeAsString(),
                parameters,
                List.of(),
                method.getDeclarationAsString(true, true, false),
                method.getJavadocComment().map(this::cleanJavadoc).orElse(""),
                callees,
                annotations
        ));
    }

    private void processConstructorDeclaration(String classSymbolId, ConstructorDeclaration constructor, String relativePath, List<UnifiedCodeSymbol> sink) {
        List<MethodParam> parameters = constructor.getParameters().stream()
                .map(p -> new MethodParam(p.getNameAsString(), p.getTypeAsString()))
                .toList();
        String paramSignature = parameters.stream().map(MethodParam::type).collect(Collectors.joining(","));
        String constructorId = String.format("%s#<init>(%s)", classSymbolId, paramSignature);

        List<String> modifiers = constructor.getModifiers().stream().map(m -> m.getKeyword().asString()).toList();
        List<String> annotations = extractAnnotations(constructor);
        List<String> callees = extractCallees(constructor);

        sink.add(new UnifiedCodeSymbol(
                constructorId,
                classSymbolId,
                "JAVA",
                constructor.getNameAsString(),
                "CONSTRUCTOR",
                relativePath,
                constructor.getBegin().map(p -> p.line).orElse(0),
                constructor.getEnd().map(p -> p.line).orElse(0),
                modifiers,
                "void",
                parameters,
                List.of(),
                constructor.getDeclarationAsString(true, true, false),
                constructor.getJavadocComment().map(this::cleanJavadoc).orElse(""),
                callees,
                annotations
        ));
    }

    /**
     * 提取方法/构造函数体内部所有调用链路（Call Reference）。
     * 遇到重载与符号推断异常，降级为裸方法名（scope.method 或 method）。
     */
    private List<String> extractCallees(Node node) {
        if (!(node instanceof com.github.javaparser.ast.body.CallableDeclaration)) {
            return List.of();
        }
        List<String> callees = new ArrayList<>();
        node.findAll(MethodCallExpr.class).forEach(call -> {
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                // 3.25.x 中 getQualifiedSignature() 直接返回 java.lang.String 全限定签名
                callees.add(resolved.getQualifiedSignature());
            } catch (Throwable t) {
                String fallback = call.getScope().map(s -> s + ".").orElse("") + call.getNameAsString();
                callees.add(fallback);
                log.trace("符号解析退化: call={}, reason={}", call, t.getMessage());
            }
        });
        return callees;
    }

    private String resolveTypeKind(TypeDeclaration<?> type) {
        if (type.isClassOrInterfaceDeclaration()) {
            return type.asClassOrInterfaceDeclaration().isInterface() ? "INTERFACE" : "CLASS";
        } else if (type.isEnumDeclaration()) {
            return "ENUM";
        } else if (type.isRecordDeclaration()) {
            return "RECORD";
        }
        return "TYPE";
    }

    /**
     * 继承关系提取：优先解析为全限定名（java.lang.Runnable），失败降级为原名。
     */
    private List<String> extractSuperTypes(TypeDeclaration<?> type) {
        List<String> superTypes = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            cid.getExtendedTypes().forEach(t -> superTypes.add(resolveFqn(t)));
            cid.getImplementedTypes().forEach(t -> superTypes.add(resolveFqn(t)));
        } else if (type instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> superTypes.add(resolveFqn(t)));
        } else if (type instanceof EnumDeclaration ed) {
            ed.getImplementedTypes().forEach(t -> superTypes.add(resolveFqn(t)));
        }
        return superTypes;
    }

    private String resolveFqn(ClassOrInterfaceType t) {
        try {
            return t.resolve().describe();
        } catch (Throwable e) {
            return t.getNameWithScope();
        }
    }

    private List<String> extractAnnotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .toList();
    }

    /** 清洗 Javadoc：去除 /**、*、*\/ 标记并合并非空行。 */
    private String cleanJavadoc(Comment comment) {
        String raw = comment.toString();
        return raw.replaceAll("/\\*\\*?", "")
                .replaceAll("\\*/", "")
                .lines()
                .map(l -> l.replaceFirst("^\\s*\\*\\s?", "").trim())
                .filter(l -> !l.isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
