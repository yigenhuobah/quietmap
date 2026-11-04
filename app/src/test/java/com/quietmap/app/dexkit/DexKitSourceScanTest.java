package com.quietmap.app.dexkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.yigenhuobah.core.test.SourceScan;

/**
 * DexKit 查询的静默失败防线（SourceScan）。
 *
 * <p>等价规则：CloudMusicTool 的 quiet 查找要求调用点带
 * {@code XposedModule/Feature/label}，否则定位失败只吞异常、不记账。QuietMap
 * 的 DexKit 兜底通过 {@code DexKitLocator.findMethod(cl, Feature, ...)} 的
 * {@code Feature} 参数把失败写进 {@link com.quietmap.app.HookState} 的 DexKit
 * 明细；一旦调用点传 {@code null}，{@code warnFailure} 只打 WARN 就返回，
 * 诊断页会看到「锚定失败」却看不到具体查询——与 CMT 的静默 quiet 查找同构。
 *
 * <p>因此用 core-test 的 {@link SourceScan} 扫描生产源码：所有
 * {@code DexKitLocator.findMethod(...)} 的第 2 个实参必须是非 {@code null}
 * 表达式；同时用计数规则证明扫描确实覆盖到了现存调用点，避免重构后测试空转。
 */
public class DexKitSourceScanTest {

    private static final Path MAIN_SOURCE = Paths.get("src/main/java");

    /** {@code DexKitLocator.findMethod} 必须带非 null Feature，失败才可归因。 */
    private static SourceScan.Rule featureRequiredRule() {
        return new SourceScan.Rule(
                "DexKitLocator\\s*\\.\\s*findMethod\\s*\\(",
                DexKitSourceScanTest::hasFeatureArgument,
                "DexKitLocator.findMethod 必须传入非 null Feature；否则失败只打 WARN，"
                        + "不会写入 HookState 的 DexKit 明细");
    }

    private static boolean hasFeatureArgument(String call) {
        List<String> args = topLevelArgs(call);
        if (args.isEmpty()) {
            // 取不到参数（例如片段只到方法名）时不误报。
            return true;
        }
        return args.size() >= 2 && !"null".equals(args.get(1).trim());
    }

    @Test
    public void productionDexKitQueriesAlwaysPassFeature() throws Exception {
        assertTrue("测试工作目录必须指向 app 模块: " + MAIN_SOURCE.toAbsolutePath(),
                MAIN_SOURCE.toFile().isDirectory());

        List<SourceScan.Finding> findings = SourceScan.scan(MAIN_SOURCE, featureRequiredRule());

        assertTrue("DexKit 查询缺少 Feature 会让失败不可见: " + findings,
                findings.isEmpty());
    }

    @Test
    public void scanActuallyCoversProductionDexKitCallSites() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SourceScan.Rule countAll = new SourceScan.Rule(
                "DexKitLocator\\s*\\.\\s*findMethod\\s*\\(",
                call -> {
                    calls.incrementAndGet();
                    return true;
                },
                "count");

        SourceScan.scan(MAIN_SOURCE, countAll);

        assertTrue("规则必须实际扫到生产 DexKit 调用点（当前 HookKit + H5InstallApk），"
                        + "否则 SourceScan 测试会空转: " + calls.get(),
                calls.get() >= 2);
    }

    @Test
    public void ruleReportsNullFeatureQuery() {
        String source = "class A { void f() { DexKitLocator.findMethod("
                + "cl, null, \"X\", \"m\", void.class, new Class<?>[0], \"a\"); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", featureRequiredRule());

        assertEquals("null Feature 的 DexKit 查询必须被报告", 1, findings.size());
    }

    @Test
    public void ruleAllowsFeatureArgumentAcrossLines() {
        String source = "class A { void f() { DexKitLocator.findMethod("
                + "cl, f,\n\"X\", \"m\", void.class, new Class<?>[0], \"a\"); } }";

        assertTrue("带 Feature 的跨行调用不得误报",
                SourceScan.scanSource(source, "A.java", featureRequiredRule()).isEmpty());
    }

    /**
     * 拆分调用的顶层实参：跳过字符串/字符字面量，括号、花括号、方括号内的
     * 逗号不分割。只用于本测试的实参形状判断。
     */
    static List<String> topLevelArgs(String call) {
        int open = call.indexOf('(');
        if (open < 0) {
            return new ArrayList<>();
        }
        int close = call.lastIndexOf(')');
        if (close < open) {
            close = call.length();
        }
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = open + 1; i < close; i++) {
            char c = call.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '\'':
                    inChar = true;
                    break;
                case '(':
                case '{':
                case '[':
                    depth++;
                    break;
                case ')':
                case '}':
                case ']':
                    depth--;
                    break;
                case ',':
                    if (depth == 0) {
                        addArg(args, call.substring(start, i));
                        start = i + 1;
                    }
                    break;
                default:
                    break;
            }
        }
        addArg(args, call.substring(start, Math.min(close, call.length())));
        return args;
    }

    private static void addArg(List<String> args, String raw) {
        String arg = raw.trim();
        if (!arg.isEmpty()) {
            args.add(arg);
        }
    }
}
