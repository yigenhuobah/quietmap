package com.quietmap.app.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.quietmap.app.Feature;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 锚点契约（纯 JVM）：anchors.json、生成的 AnchorKeys.java 与 hook 源码三方一致。
 *
 * <p>覆盖：常量 ↔ 表双向一致、源码引用的常量存在、每个常量都被消费、
 * hooks/ 不散落宿主类/方法字面量、遗留的字面量 key 仍在表里、
 * 每个锚点都有 feature/critical/note，且 feature 是 {@link Feature} 里的真实编号。
 * 这样锚点 key 改名只会在编译期/单测失败，而不是运行时静默 fail-open。
 *
 * <p>Android 单测里的 org.json 可能是 stub，这里用最小 JSON 解析器读取资源，
 * 保证契约在纯 JVM 下真实生效。
 */
public class AnchorContractTest {

    private static final int EXPECTED_ANCHOR_COUNT = 63;

    /** hosts/ 里允许出现的宿主 FQCN 字面量形状（com.*）。 */
    private static final Pattern HOST_CLASS_LITERAL =
            Pattern.compile("^com\\.[A-Za-z0-9_$.]+$");
    /** 默认包混淆类名形状（ec0 / ah5 / n8 / p75 / zg2 等）。 */
    private static final Pattern OBFUSCATED_CLASS_LITERAL =
            Pattern.compile("^(?=[a-z0-9]*[0-9])[a-z][a-z0-9]{0,5}$");
    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** Class.forName("...") 形式的硬编码类名。 */
    private static final Pattern CLASS_LOAD_CALL =
            Pattern.compile("\\bClass\\s*\\.\\s*forName\\s*\\(\\s*\"([^\"]+)\"");
    /** 反射方法名："findMethod(..., \"x\")" / "getDeclaredMethod(\"x\")" 等。 */
    private static final Pattern METHOD_NAME_CALL = Pattern.compile(
            "\\b(?:findMethod|findMethodQuiet|getDeclaredMethod|getMethod)"
                    + "\\s*\\([^;\\n]{0,160}?\"([A-Za-z_$][A-Za-z0-9_$]*)\"");
    /** 旧式硬编码挂载调用：hook 必须走 AnchorTable/AnchorKeys。 */
    private static final Pattern MOUNT_CALL =
            Pattern.compile("\\bHookKit\\s*\\.\\s*(?:findClass|findMethod)\\s*\\(");
    private static final String ALLOWLIST_MARKER = "anchor-allowlist:";

    private static Map<String, Object> loadAnchors() throws Exception {
        InputStream stream = AnchorContractTest.class.getClassLoader()
                .getResourceAsStream("anchors.json");
        assertNotNull("anchors.json 必须在测试 classpath 上", stream);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        Object parsed = new JsonParser(text.toString()).parse();
        assertTrue("anchors.json 顶层必须是对象", parsed instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) parsed;
        return json;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue("期望 JSON 对象，实际是 " + value, value instanceof Map);
        return (Map<String, Object>) value;
    }

    private static Set<String> tableKeys(Map<String, Object> json) {
        Set<String> keys = new HashSet<>();
        keys.addAll(object(json.get("anchors")).keySet());
        keys.addAll(object(json.get("classes")).keySet());
        return keys;
    }

    /** 与 AnchorKeys 生成规则保持同一规则。 */
    private static String constantName(String key) {
        return key.replaceAll("[^0-9A-Za-z]+", "_").toUpperCase();
    }

    /** AnchorKeys 的常量名 → 常量值（锚点 key）。 */
    private static Map<String, String> anchorKeys() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (Field field : AnchorKeys.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers)) {
                continue;
            }
            assertTrue("AnchorKeys." + field.getName() + " 必须是 public",
                    Modifier.isPublic(modifiers));
            assertTrue("AnchorKeys." + field.getName() + " 必须是 final",
                    Modifier.isFinal(modifiers));
            assertEquals("AnchorKeys." + field.getName() + " 必须是 String",
                    String.class, field.getType());
            field.setAccessible(true);
            out.put(field.getName(), (String) field.get(null));
        }
        return out;
    }

    private static List<String> strings(Object value) {
        assertTrue("期望字符串数组，实际是 " + value, value instanceof List);
        List<?> list = (List<?>) value;
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            assertTrue("数组元素必须是非空字符串: " + item,
                    item instanceof String && !((String) item).isEmpty());
            out.add((String) item);
        }
        return out;
    }

    @Test
    public void schemaAndMetadataAreVersioned() throws Exception {
        Map<String, Object> json = loadAnchors();
        assertEquals("schemaVersion 必须为 1", 1L, json.get("schemaVersion"));
        assertEquals("generatedBy 必须指向生成器",
                "tools/gen_anchors.py", json.get("generatedBy"));
        assertTrue("appVersion 必须是非空字符串",
                json.get("appVersion") instanceof String
                        && !((String) json.get("appVersion")).isEmpty());
        assertTrue("versionCode 必须是正整数",
                json.get("versionCode") instanceof Long
                        && (Long) json.get("versionCode") > 0);
        assertTrue("anchors 段必须是对象", json.get("anchors") instanceof Map);
        assertTrue("classes 段必须是对象", json.get("classes") instanceof Map);
    }

    @Test
    public void anchorCountIs63() throws Exception {
        Map<String, Object> anchors = object(loadAnchors().get("anchors"));
        assertEquals("check-mounts 的 63 项校验必须全部收敛进锚点表",
                EXPECTED_ANCHOR_COUNT, anchors.size());
    }

    @Test
    public void anchorKeysAndTableAreBidirectional() throws Exception {
        Map<String, Object> json = loadAnchors();
        Set<String> keys = tableKeys(json);
        Map<String, String> constants = anchorKeys();

        Set<String> constantValues = new HashSet<>(constants.values());
        for (String key : keys) {
            assertTrue("anchors.json 的 key 缺少 AnchorKeys 常量：" + key,
                    constantValues.contains(key));
            assertTrue("常量名不符合生成规则: " + key + " -> " + constantName(key),
                    constants.containsKey(constantName(key)));
        }
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            assertTrue("AnchorKeys." + entry.getKey() + " 指向表中不存在的 key："
                            + entry.getValue(),
                    keys.contains(entry.getValue()));
        }
    }

    @Test
    public void everyAnchorHasFeatureCriticalAndNote() throws Exception {
        Map<String, Object> json = loadAnchors();
        Set<String> featureCodes = new HashSet<>();
        for (Feature feature : Feature.values()) {
            featureCodes.add(feature.id);
        }

        for (Map.Entry<String, Object> item : object(json.get("anchors")).entrySet()) {
            String key = item.getKey();
            Map<String, Object> entry = object(item.getValue());
            for (String field : new String[]{"c", "m", "s", "feature", "note"}) {
                Object value = entry.get(field);
                assertTrue("锚点 " + key + " 缺少 " + field,
                        value instanceof String && !((String) value).isEmpty());
            }
            String feature = (String) entry.get("feature");
            assertTrue("锚点 " + key + " 的 feature 不是已知功能编号：" + feature,
                    featureCodes.contains(feature));
            assertTrue("锚点 " + key + " 的 critical 必须是布尔值",
                    entry.get("critical") instanceof Boolean);
            assertTrue("锚点 " + key + " 的 s 必须是方法描述符（以 '(' 开头）："
                    + entry.get("s"), ((String) entry.get("s")).startsWith("("));
        }
    }

    /**
     * QM 的混淆类/结构锚点必须保留在表里：candidates 是类名快路径，
     * dexkit 是 DexKit 方法体字符串兜底。三个 DexKit 调用点逐字锁定。
     */
    @Test
    public void candidatesAndDexkitPreserved() throws Exception {
        Map<String, Object> anchors = object(loadAnchors().get("anchors"));

        assertAnchorExtras(anchors, "splash_attempt_request_data",
                Arrays.asList("ah5"), Arrays.asList("不发送请求"));
        assertAnchorExtras(anchors, "msg_image_text_download_run",
                Arrays.asList("n8"),
                Arrays.asList("invalid download item: ", "ImageTextMessageDownloader"));
        assertAnchorExtras(anchors, "h5_dispatch_gate",
                Arrays.asList("ec0"),
                Arrays.asList("action.autonavi.checkappupdate",
                        "Dispatch App Upgrade", "appDownloadfail"));
        assertAnchorExtras(anchors, "delivery_display_start",
                Arrays.asList("ev1"), null);
        assertAnchorExtras(anchors, "dialog_conflict_add_to_queue",
                Arrays.asList("ry1"), null);
        assertAnchorExtras(anchors, "notify_permission_bridge",
                Arrays.asList("pp2"), null);
    }

    private static void assertAnchorExtras(Map<String, Object> anchors, String key,
                                           List<String> candidates, List<String> dexkit) {
        Map<String, Object> entry = object(anchors.get(key));
        assertNotNull("锚点 " + key + " 必须存在", entry);
        assertEquals("锚点 " + key + " 的 candidates 不符",
                candidates, strings(entry.get("candidates")));
        if (dexkit != null) {
            assertEquals("锚点 " + key + " 的 dexkit 不符",
                    dexkit, strings(entry.get("dexkit")));
        }
    }

    @Test
    public void obfuscatedAnchorsCarryCandidates() throws Exception {
        for (Map.Entry<String, Object> item
                : object(loadAnchors().get("anchors")).entrySet()) {
            Map<String, Object> entry = object(item.getValue());
            String className = (String) entry.get("c");
            if (className.indexOf('.') >= 0) {
                continue;
            }
            assertTrue("混淆锚点 " + item.getKey() + " 必须带 candidates 类名快路径",
                    entry.get("candidates") instanceof List
                            && !((List<?>) entry.get("candidates")).isEmpty());
        }
    }

    @Test
    public void sourceAnchorKeysReferencesExist() throws Exception {
        Set<String> constants = anchorKeys().keySet();
        Pattern reference = Pattern.compile("\\bAnchorKeys\\.([A-Za-z_][A-Za-z0-9_]*)");
        boolean sawReference = false;

        for (Path source : javaSources()) {
            String text = read(source);
            Matcher matcher = reference.matcher(text);
            while (matcher.find()) {
                sawReference = true;
                String name = matcher.group(1);
                assertTrue("源码引用了不存在的 AnchorKeys." + name + "：" + source,
                        constants.contains(name));
            }
        }
        assertTrue("源码中应至少有一处 AnchorKeys 引用（生成器/契约失效？）", sawReference);
    }

    /**
     * 每个生成的 AnchorKeys 常量都必须在生产代码里被引用：hooks 改为
     * AnchorTable/AnchorKeys 后，未引用说明表里有死锚点或迁移漏项。
     * blocked 功能的锚点由 Hooks.BLOCKED_ANCHORS 集中引用。
     */
    @Test
    public void everyAnchorKeyConstantIsConsumed() throws Exception {
        Set<String> referenced = new HashSet<>();
        Pattern reference = Pattern.compile("\\bAnchorKeys\\.([A-Za-z_][A-Za-z0-9_]*)");
        for (Path source : javaSources()) {
            Matcher matcher = reference.matcher(read(source));
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }
        for (String name : anchorKeys().keySet()) {
            assertTrue("AnchorKeys." + name + " 未被任何生产代码引用（删除或接入锚点）",
                    referenced.contains(name));
        }
    }

    /**
     * hooks/ 下不得再散落宿主类名/方法名字面量：
     * - HookKit.findClass/findMethod 旧式硬编码挂载调用一律禁止；
     * - Class.forName("com...") / 混淆短类名必须来自锚点表；
     * - 反射方法名不在 anchors.json 的 m 集合里即视为新硬编码；
     * - 注释以 {@code // anchor-allowlist: <原因>} 豁免（同一行或上一行），
     *   原因必须写明，不允许空豁免。
     */
    @Test
    public void hooksDoNotHardcodeHostClassOrMethodLiterals() throws Exception {
        Map<String, Object> json = loadAnchors();
        Set<String> knownClasses = new HashSet<>();
        Set<String> knownMethods = new HashSet<>();
        for (Map.Entry<String, Object> item : object(json.get("anchors")).entrySet()) {
            Map<String, Object> entry = object(item.getValue());
            Object className = entry.get("c");
            if (className instanceof String) {
                knownClasses.add((String) className);
            }
            Object candidates = entry.get("candidates");
            if (candidates instanceof List) {
                knownClasses.addAll(strings(candidates));
            }
            Object methodName = entry.get("m");
            if (methodName instanceof String) {
                knownMethods.add((String) methodName);
            }
        }
        for (Object className : object(json.get("classes")).values()) {
            if (className instanceof String) {
                knownClasses.add((String) className);
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path source : hookSources()) {
            String text = read(source);
            String code = stripComments(text);
            String[] originalLines = text.split("\n", -1);

            Matcher strings = STRING_LITERAL.matcher(code);
            while (strings.find()) {
                int line = lineOf(code, strings.start());
                String literal = unescapeJava(strings.group(1));
                String reason = allowlistReason(originalLines, line);
                if (isHostClassLiteral(literal) && !knownClasses.contains(literal)
                        && reason == null) {
                    violations.add(location(source, line)
                            + " 宿主类名字面量不在 anchors.json：" + literal);
                }
            }

            Matcher load = CLASS_LOAD_CALL.matcher(code);
            while (load.find()) {
                int line = lineOf(code, load.start());
                String literal = unescapeJava(load.group(1));
                if (isHostClassLiteral(literal)
                        && allowlistReason(originalLines, line) == null) {
                    violations.add(location(source, line)
                            + " 硬编码类加载字面量：" + literal);
                }
            }

            Matcher methodCall = METHOD_NAME_CALL.matcher(code);
            while (methodCall.find()) {
                int line = lineOf(code, methodCall.start());
                String literal = unescapeJava(methodCall.group(1));
                if (!knownMethods.contains(literal)
                        && allowlistReason(originalLines, line) == null) {
                    violations.add(location(source, line)
                            + " 反射方法名不在 anchors.json：" + literal);
                }
            }

            Matcher mountCall = MOUNT_CALL.matcher(code);
            while (mountCall.find()) {
                int line = lineOf(code, mountCall.start());
                if (allowlistReason(originalLines, line) == null) {
                    violations.add(location(source, line)
                            + " HookKit.findClass/findMethod 硬编码挂载调用必须改用 AnchorKeys");
                }
            }
        }
        assertTrue("hooks/ 存在未入锚点表的宿主类/方法字面量：\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    private static boolean isHostClassLiteral(String literal) {
        return HOST_CLASS_LITERAL.matcher(literal).matches()
                || OBFUSCATED_CLASS_LITERAL.matcher(literal).matches();
    }

    private static String location(Path source, int line) {
        return source.getFileName() + ":" + line;
    }

    /** 1-based 行号：offset 之前换行数 + 1。 */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 返回同一行或上一行的 allowlist 原因；原因少于 8 个字符视为无效豁免。
     */
    private static String allowlistReason(String[] lines, int lineNumber) {
        for (int index = lineNumber - 1; index >= Math.max(0, lineNumber - 2); index--) {
            int marker = lines[index].indexOf(ALLOWLIST_MARKER);
            if (marker < 0) {
                continue;
            }
            String reason = lines[index].substring(marker + ALLOWLIST_MARKER.length()).trim();
            if (reason.length() >= 8) {
                return reason;
            }
        }
        return null;
    }

    /**
     * 去掉 // 与 /* *\/ 注释，保留字符串/字符字面量与换行（行号不漂移）。
     * 这样宿主类名只出现在 Javadoc 里不会被误判成硬编码。
     */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    out.append("  ");
                    i++;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (string) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(next);
                    i++;
                } else if (c == '"') {
                    string = false;
                }
                continue;
            }
            if (character) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(next);
                    i++;
                } else if (c == '\'') {
                    character = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                out.append("  ");
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                out.append("  ");
                i++;
            } else if (c == '"') {
                string = true;
                out.append(c);
            } else if (c == '\'') {
                character = true;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 仅处理测试里可能出现的转义；未知转义原样保留。 */
    static String unescapeJava(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char escaped = raw.charAt(++i);
            switch (escaped) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default:
                    out.append(escaped);
            }
        }
        return out.toString();
    }

    private static List<Path> hookSources() throws Exception {
        Path hooks = mainSourceRoot().resolve("com").resolve("quietmap").resolve("app")
                .resolve("hooks");
        assertTrue("找不到 hooks 源码目录：" + hooks, Files.isDirectory(hooks));
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(hooks)) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }


    @Test
    public void legacyLiteralAnchorKeysExistInTable() throws Exception {
        Set<String> keys = tableKeys(loadAnchors());
        Pattern literal = Pattern.compile(
                "\\banchors\\s*\\.\\s*(?:method|methodForCandidates|methodName|candidates|dexkit|clazz|field)"
                        + "\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
        for (Path source : javaSources()) {
            Matcher matcher = literal.matcher(read(source));
            while (matcher.find()) {
                String key = matcher.group(1);
                assertTrue("源码字面量锚点不在表里：" + key + "（" + source + "）",
                        keys.contains(key));
            }
        }
    }

    /** method/methodForCandidates/field 访问器必须使用对应段：method→anchors，clazz→classes。 */
    @Test
    public void anchorAccessorsUseCorrectSections() throws Exception {
        Map<String, Object> json = loadAnchors();
        Map<String, Object> anchors = object(json.get("anchors"));
        Map<String, Object> classes = object(json.get("classes"));
        Map<String, String> constants = anchorKeys();
        Set<String> fieldKeys = new HashSet<>();
        for (Map.Entry<String, Object> entry : anchors.entrySet()) {
            Object signature = object(entry.getValue()).get("s");
            if (signature instanceof String && !((String) signature).startsWith("(")) {
                fieldKeys.add(entry.getKey());
            }
        }

        Pattern accessor = Pattern.compile(
                "\\banchors\\s*\\.\\s*(method|methodForCandidates|methodName|candidates|dexkit|clazz|field)"
                        + "\\s*\\(\\s*AnchorKeys\\.([A-Za-z0-9_]+)\\s*\\)");
        for (Path source : javaSources()) {
            Matcher matcher = accessor.matcher(read(source));
            while (matcher.find()) {
                String kind = matcher.group(1);
                String constant = matcher.group(2);
                String key = constants.get(constant);
                assertNotNull("引用了不存在的 AnchorKeys." + constant + "：" + source, key);
                if ("candidates".equals(kind) || "dexkit".equals(kind)
                        || "methodName".equals(kind)) {
                    assertTrue("anchors." + kind + " 使用了 anchors 段之外的 key：" + key
                            + "（" + source + "）", anchors.containsKey(key));
                } else if ("field".equals(kind)) {
                    assertTrue("anchors.field 使用了非字段锚点 key：" + key + "（"
                            + source + "）", fieldKeys.contains(key));
                } else if ("clazz".equals(kind)) {
                    assertTrue("anchors.clazz 使用了 classes 段之外的 key：" + key + "（"
                            + source + "）", classes.containsKey(key));
                } else {
                    assertTrue("anchors." + kind + " 使用了 anchors 段之外的 key：" + key
                            + "（" + source + "）", anchors.containsKey(key));
                }
            }
        }
    }

    @Test
    public void constantNamesMatchGeneratedRule() throws Exception {
        for (Map.Entry<String, String> entry : anchorKeys().entrySet()) {
            assertEquals("常量名必须是 key 的大写下划线形式",
                    constantName(entry.getValue()), entry.getKey());
        }
    }

    /** 从测试工作目录向上找到 app/src/main/java（Gradle 单测 cwd 多为模块目录）。 */
    private static Path mainSourceRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path direct = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path module = dir.resolve("app").resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(module)) {
                return module;
            }
            dir = dir.getParent();
        }
        fail("找不到 app/src/main/java（cwd=" + System.getProperty("user.dir") + "）");
        return null;
    }

    private static List<Path> javaSources() throws Exception {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(mainSourceRoot())) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 最小 JSON 解析器：对象/数组/字符串/数字/布尔/null，足够覆盖 anchors.json。 */
    static final class JsonParser {
        private final String text;
        private int index;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("JSON 尾部有多余内容: " + index);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new HashMap<>();
            index++; // {
            skipWhitespace();
            if (peek('}')) {
                index++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                require(':');
                Object value = parseValue();
                if (out.put(key, value) != null) {
                    throw new IllegalArgumentException("重复键: " + key);
                }
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                require('}');
                return out;
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            index++; // [
            skipWhitespace();
            if (peek(']')) {
                index++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                require(']');
                return out;
            }
        }

        private String parseString() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("字符串转义未结束");
                }
                char esc = text.charAt(index++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(esc);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("\\u 转义不完整");
                        }
                        out.append((char) Integer.parseInt(
                                text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("未知转义: \\" + esc);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNumber() {
            int start = index;
            while (index < text.length()
                    && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("非法 JSON 值，位置 " + start);
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                return Long.parseLong(raw);
            }
            return Double.parseDouble(raw);
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char c) {
            return index < text.length() && text.charAt(index) == c;
        }

        private void require(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException(
                        "期望 '" + c + "'，位置 " + index);
            }
            index++;
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException(
                        "期望 " + literal + "，位置 " + index);
            }
            index += literal.length();
        }
    }
}
