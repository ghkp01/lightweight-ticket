package com.mes.architecture;

import com.mes.platform.module.ModuleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构守卫：把「将来能拆成微服务」这件事变成可执行的规则，而不是口头约定。
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>跨模块只能依赖对方的 api 包</b>：A 模块 import {@code com.mes.B.InventoryService} 违规，
 *       import {@code com.mes.B.api.SomePort} 合规。这样拆分时把 api 包挖出去即可；</li>
 *   <li><b>内联全限定名同样受限</b>：写 {@code com.mes.B.InventoryService} 当类型用，效果和 import 一样，
 *       但只认 import 的规则看不见它 —— 这条把洞补上（注释与字符串不算）；</li>
 *   <li><b>只能写自己的表</b>：模块 SQL 里出现别的模块拥有的表就违规，
 *       否则将来分库时这条 SQL 直接崩。</li>
 * </ol>
 *
 * <p>存量违规记在 {@code src/test/resources/architecture-baseline.txt} 里，测试只拦「新增」。
 * 这是刻意的：一次性改完 300 处不现实，但至少不会继续变坏。
 * 每清理掉一批，就把对应行从 baseline 删掉，基线只会缩不会涨。</p>
 *
 * <p>基线文件不存在时会自动生成一次（首次接入用），之后就该进版本库。</p>
 */
class ArchitectureBoundaryTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java/com/mes");
    private static final Path BASELINE = Paths.get("src/test/resources/architecture-baseline.txt");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+com\\.mes\\.([a-zA-Z0-9_.]+);");
    private static final Pattern INLINE_REFERENCE = Pattern.compile(
            "\\bcom\\.mes\\.([a-z][a-z0-9_]*)\\.([A-Za-z0-9_$]+)");
    private static final Pattern TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update|delete\\s+from)\\s+([a-z_][a-z0-9_]*)");

    @Test
    @DisplayName("跨模块依赖只能指向对方的 api 包")
    void crossModuleImportsStayOnApi() {
        Set<String> allowed = loadBaseline();
        assertNoNewViolations("跨模块依赖", importViolations(), allowed);
    }

    @Test
    @DisplayName("内联写全限定名同样不能绕过 api 包约束")
    void inlineFullyQualifiedReferencesStayOnApi() {
        Set<String> allowed = loadBaseline();
        assertNoNewViolations("内联全限定名", inlineReferenceViolations(), allowed);
    }

    @Test
    @DisplayName("每个模块只能读写自己拥有的表")
    void modulesOnlyTouchOwnedTables() {
        Set<String> allowed = loadBaseline();
        assertNoNewViolations("跨模块访问表", tableViolations(), allowed);
    }

    // ------------------------------------------------------------------ 内部

    /** 规则一：A 模块 import 了 B 模块除 api 以外的包 */
    private List<String> importViolations() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String module = moduleOf(file);
            if (module == null) {
                continue;
            }
            for (String target : importsOf(file)) {
                String owner = target.contains(".") ? target.substring(0, target.indexOf('.')) : target;
                if (owner.equals(module) || ModuleCatalog.PLATFORM_PACKAGES.contains(owner)
                        || !ModuleCatalog.isModule(owner)) {
                    continue;
                }
                String rest = target.length() > owner.length() + 1
                        ? target.substring(owner.length() + 1) : "";
                if (rest.equals("api") || rest.startsWith("api.")) {
                    continue;
                }
                violations.add(key("IMPORT", file, target));
            }
        }
        return violations;
    }

    /**
     * 规则二：把全限定名内联写进代码（`com.mes.push.PushService pushService`）同样算跨模块依赖。
     *
     * <p>import 规则只看 {@code import} 行，内联写法能整片绕过去 —— 之前 {@code system.OrgScopeService}
     * 就是这样被 13 个模块引用的，基线数字看着漂亮，实际耦合一点没少。这里扫代码正文：
     * 先剥掉块注释、行注释与 import 行（import 由规则一负责，避免重复记两条）。</p>
     */
    private List<String> inlineReferenceViolations() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String module = moduleOf(file);
            if (module == null) {
                continue;
            }
            Matcher matcher = INLINE_REFERENCE.matcher(codeWithoutCommentsAndImports(file));
            while (matcher.find()) {
                String owner = matcher.group(1);
                String target = owner + "." + matcher.group(2);
                if (owner.equals(module) || ModuleCatalog.PLATFORM_PACKAGES.contains(owner)
                        || !ModuleCatalog.isModule(owner)) {
                    continue;
                }
                if (matcher.group(2).equals("api")) {
                    continue;
                }
                violations.add(key("INLINE", file, target));
            }
        }
        return violations;
    }

    /** 规则三：模块 SQL 里出现了别的模块拥有的表 */
    private List<String> tableViolations() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String module = moduleOf(file);
            if (module == null) {
                continue;
            }
            Matcher matcher = TABLE.matcher(codeWithoutCommentsAndImports(file));
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase(Locale.ROOT);
                String owner = ModuleCatalog.ownerOfTable(table);
                if (owner == null || owner.equals(module)) {
                    continue;
                }
                violations.add(key("TABLE", file, table + " (owned by " + owner + ")"));
            }
        }
        return violations;
    }

    private void assertNoNewViolations(String label, List<String> violations, Set<String> allowed) {
        Set<String> unique = new LinkedHashSet<>(violations);
        List<String> fresh = unique.stream().filter(item -> !allowed.contains(item)).sorted().toList();
        String sample = fresh.stream().limit(10).collect(Collectors.joining("\n  "));
        assertTrue(fresh.isEmpty(), label + "出现 " + fresh.size() + " 处新增越界（存量 "
                + allowed.size() + " 条已记入基线）：\n  " + sample);
    }

    /**
     * 基线缺失时生成一份并放过本次断言：首次接入架构守卫时用，之后就靠它守住不退化。
     */
    private Set<String> loadBaseline() {
        if (!Files.exists(BASELINE)) {
            try {
                Files.createDirectories(BASELINE.getParent());
                List<String> lines = new ArrayList<>();
                lines.add("# 架构守卫基线：存量越界，只允许减少不允许新增。清理掉一处就删掉对应行。");
                lines.addAll(importViolations());
                lines.addAll(inlineReferenceViolations());
                lines.addAll(tableViolations());
                List<String> written = lines.stream().distinct().sorted().toList();
                Files.write(BASELINE, written, StandardCharsets.UTF_8);
                // 首次生成即视为「存量」，本次不判失败，之后靠它守住不退化
                return written.stream()
                        .filter(line -> !line.startsWith("#"))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        try {
            return Files.readAllLines(BASELINE, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String key(String kind, Path file, String detail) {
        String relative = SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
        return kind + " " + relative + " -> " + detail;
    }

    private List<Path> javaFiles() {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            return stream.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** 文件所属的一级包名；平台层包也算，规则里会放行 */
    private String moduleOf(Path file) {
        Path relative = SOURCE_ROOT.relativize(file);
        return relative.getNameCount() < 2 ? null : relative.getName(0).toString();
    }

    private List<String> importsOf(Path file) {
        List<String> result = new ArrayList<>();
        for (String line : read(file).split("\\R")) {
            Matcher matcher = IMPORT.matcher(line.trim());
            if (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return result;
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** 代码正文：剥掉块注释（含 javadoc）、行注释与 import 行 */
    private String codeWithoutCommentsAndImports(Path file) {
        String text = read(file).replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                continue;
            }
            int comment = line.indexOf("//");
            code.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return code.toString();
    }
}
