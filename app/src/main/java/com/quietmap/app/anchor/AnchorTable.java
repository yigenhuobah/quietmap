package com.quietmap.app.anchor;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 锚点表：app/src/main/resources/anchors.json 的运行时 reader。
 *
 * <p>方法锚点按 {@code c}/{@code m}/{@code s} 反射解析；混淆类还可以用
 * {@code candidates}（候选 Java 类名）与 {@code dexkit}（DexKit 方法体字符串）
 * 兜底。任一锚点失效时只影响对应功能，所有失败路径都 fail-open 返回 null。
 *
 * <p>缓存只保存解析成功的结果：缺失锚点每次重试，避免一次瞬时失败（例如
 * 类尚未加载）在进程内永久禁用该锚点。
 */
public final class AnchorTable {

    private static final String TAG = "QuietMap";
    private static final String[] EMPTY_STRINGS = new String[0];

    /** 日志出口：单测可替换为抛异常实现，验证日志失败不影响锚点解析（fail-open）。 */
    interface LogSink {
        void warn(String tag, String message);
    }

    private static final LogSink DEFAULT_LOG_SINK = (tag, message) -> Log.w(tag, message);
    private static volatile LogSink logSink = DEFAULT_LOG_SINK;

    /** 仅供 JVM 单测：替换日志出口，传 null 恢复默认。 */
    static void setLogSinkForTest(LogSink sink) {
        logSink = sink == null ? DEFAULT_LOG_SINK : sink;
    }

    /**
     * 锚点解析失败类别：WARN 日志与单测共用，避免“只返回 null”无法定位问题。
     * 标签是 logcat 英文文案，保持小写、可 grep。
     */
    enum Failure {
        MISSING_KEY("missing key"),
        CLASS_NOT_FOUND("class not found"),
        METHOD_NOT_FOUND("method not found"),
        FIELD_NOT_FOUND("field not found"),
        SIGNATURE_MISMATCH("signature mismatch"),
        RETURN_TYPE_MISMATCH("return type mismatch"),
        FIELD_TYPE_MISMATCH("field type mismatch"),
        ERROR("resolve error");

        final String label;

        Failure(String label) {
            this.label = label;
        }
    }

    /** 解析结果：成功时 value 非 null、failure 为 null；失败时反之。 */
    static final class Lookup<T> {
        final T value;
        final Failure failure;
        final Throwable error;

        Lookup(T value, Failure failure, Throwable error) {
            this.value = value;
            this.failure = failure;
            this.error = error;
        }
    }

    private final String appVersion;
    private final long versionCode;
    private final JSONObject root;
    private final ClassLoader loader;
    private final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Field> fieldCache = new ConcurrentHashMap<>();
    /** 已写过的 WARN 文案：同一失败只记一次，避免逐条判定时刷屏。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    AnchorTable(String appVersion, long versionCode, JSONObject root, ClassLoader loader) {
        this.appVersion = appVersion;
        this.versionCode = versionCode;
        this.root = root;
        this.loader = loader;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public long getVersionCode() {
        return versionCode;
    }

    /**
     * 按表内 {@code c}/{@code m}/{@code s} 解析方法。
     * 混淆类版本升级改名后 {@code c} 会先失效，此时用
     * {@link #methodForCandidates(String)} 走 {@code candidates} 列表。
     */
    public Method method(String key) {
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        JSONObject entry = entry(key);
        if (entry == null) {
            return null;
        }
        Lookup<Method> lookup = resolveMethod(this, loader, key, entry.optString("c"),
                entry.optString("m"), entry.optString("s"));
        if (lookup.value == null) {
            return null;
        }
        if (!makeAccessible(this, key, lookup.value)) {
            return null;
        }
        methodCache.put(key, lookup.value);
        return lookup.value;
    }

    /**
     * 按 {@code candidates} 候选类名逐个解析 {@code m}/{@code s}。
     * 候选顺序即优先级；第一个成功且返回类型匹配的候选胜出，其余候选不再尝试。
     * 无 candidates 字段（非混淆锚点）或全部候选失败时返回 null。
     */
    public Method methodForCandidates(String key) {
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        JSONObject entry = entry(key);
        if (entry == null) {
            return null;
        }
        String[] names = stringArray(entry, key, "candidates");
        if (names.length == 0) {
            warn(key, Failure.MISSING_KEY, "empty candidates", null);
            return null;
        }
        String methodName = entry.optString("m");
        String signature = entry.optString("s");
        for (String name : names) {
            Class<?> cls;
            try {
                cls = Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                warn(key, Failure.CLASS_NOT_FOUND, name, e);
                continue;
            } catch (Throwable t) {
                warn(key, Failure.ERROR, name, t);
                continue;
            }
            Lookup<Method> lookup = resolveMethodOnClass(this, loader, key, cls,
                    methodName, signature);
            if (lookup.value != null) {
                if (!makeAccessible(this, key, lookup.value)) {
                    return null;
                }
                methodCache.put(key, lookup.value);
                return lookup.value;
            }
        }
        return null;
    }

    /** 类锚点（classes 段）。 */
    public Class<?> clazz(String key) {
        Class<?> cached = classCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            JSONObject classes = root.optJSONObject("classes");
            if (classes == null) {
                warn(key, Failure.MISSING_KEY, "missing classes section", null);
                return null;
            }
            String name = classes.optString(key, "");
            if (name.isEmpty()) {
                warn(key, Failure.MISSING_KEY, "missing class entry", null);
                return null;
            }
            Lookup<Class<?>> lookup = resolveClass(this, loader, key, name);
            if (lookup.value != null) {
                classCache.put(key, lookup.value);
            }
            return lookup.value;
        } catch (Throwable t) {
            warn(key, Failure.ERROR, "class anchor resolve error", t);
            return null;
        }
    }

    /**
     * 字段锚点：{@code s} 不以 "(" 开头时按字段类型描述符解析。
     * 用于宿主混名前字段（字段名/类型都可能变），失败同样 fail-open 返回 null。
     */
    public Field field(String key) {
        Field cached = fieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        JSONObject entry = entry(key);
        if (entry == null) {
            return null;
        }
        Lookup<Field> lookup = resolveField(this, loader, key, entry.optString("c"),
                entry.optString("m"), entry.optString("s"));
        if (lookup.value == null) {
            return null;
        }
        if (!makeAccessible(this, key, lookup.value)) {
            return null;
        }
        fieldCache.put(key, lookup.value);
        return lookup.value;
    }

    /** 锚点方法名（DexKit 查询提示）；key 不存在时返回空串。 */
    public String methodName(String key) {
        JSONObject entry = entry(key);
        return entry == null ? "" : entry.optString("m");
    }

    /** 候选 Java 类名列表；没有 candidates 字段时返回空数组。 */
    public String[] candidates(String key) {
        JSONObject entry = entry(key);
        return entry == null ? EMPTY_STRINGS : stringArray(entry, key, "candidates");
    }

    /** DexKit 方法体字符串锚点；没有 dexkit 字段时返回空数组。 */
    public String[] dexkit(String key) {
        JSONObject entry = entry(key);
        return entry == null ? EMPTY_STRINGS : stringArray(entry, key, "dexkit");
    }

    public boolean isSupportedVersion(String installedVersion) {
        return isSupportedVersion(appVersion, installedVersion);
    }

    /** 版本门禁规则：未知版本 fail-open；已知版本必须精确匹配。 */
    static boolean isSupportedVersion(String expectedVersion, String installedVersion) {
        return installedVersion == null || installedVersion.equals(expectedVersion);
    }

    public static AnchorTable load(ClassLoader loader) {
        try {
            InputStream stream = AnchorTable.class.getClassLoader()
                    .getResourceAsStream("anchors.json");
            if (stream == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    text.append(buffer, 0, read);
                }
            }
            JSONObject json = new JSONObject(text.toString());
            // schemaVersion 是四模块共同契约；不认识的版本宁可整表 fail-open，
            // 也不按旧字段猜读（字段语义可能已变）。
            int schema = json.optInt("schemaVersion", -1);
            if (schema != 1) {
                Log.w(TAG, "unsupported anchors.json schemaVersion=" + schema);
                return null;
            }
            return new AnchorTable(json.optString("appVersion"),
                    json.optLong("versionCode"), json, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private JSONObject entry(String key) {
        try {
            JSONObject anchors = root.optJSONObject("anchors");
            if (anchors == null) {
                warn(key, Failure.MISSING_KEY, "missing anchors section", null);
                return null;
            }
            JSONObject entry = anchors.optJSONObject(key);
            if (entry == null) {
                warn(key, Failure.MISSING_KEY, "missing anchor entry", null);
                return null;
            }
            return entry;
        } catch (Throwable t) {
            warn(key, Failure.ERROR, "anchor entry resolve error", t);
            return null;
        }
    }

    /** 读取字符串数组；非法元素记录 WARN 后跳过，整体保持 fail-open。 */
    private static String[] stringArray(JSONObject entry, String key, String field) {
        JSONArray array = entry.optJSONArray(field);
        if (array == null) {
            return EMPTY_STRINGS;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String && !((String) value).isEmpty()) {
                out.add((String) value);
            } else {
                warn(null, key, Failure.SIGNATURE_MISMATCH,
                        field + "[" + i + "] must be a non-empty string", null);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 解析目标类：类不存在、类名为空与其它反射异常分别记录 WARN。
     * 静态方法便于 JVM 单测直接覆盖失败原因，不依赖 JSON。
     */
    static Lookup<Class<?>> resolveClass(ClassLoader loader, String key, String className) {
        return resolveClass(null, loader, key, className);
    }

    private static Lookup<Class<?>> resolveClass(AnchorTable owner, ClassLoader loader,
                                                 String key, String className) {
        if (className == null || className.isEmpty()) {
            warn(owner, key, Failure.MISSING_KEY, "empty class name", null);
            return new Lookup<>(null, Failure.MISSING_KEY, null);
        }
        try {
            return new Lookup<>(Class.forName(className, false, loader), null, null);
        } catch (ClassNotFoundException e) {
            warn(owner, key, Failure.CLASS_NOT_FOUND, className, e);
            return new Lookup<>(null, Failure.CLASS_NOT_FOUND, e);
        } catch (Throwable t) {
            warn(owner, key, Failure.ERROR, className, t);
            return new Lookup<>(null, Failure.ERROR, t);
        }
    }

    /**
     * 解析目标字段：沿类继承链查找，字段缺失与类型不符分别记录 WARN。
     * 静态方法便于 JVM 单测直接覆盖失败原因，不依赖 JSON。
     */
    static Lookup<Field> resolveField(ClassLoader loader, String key, String className,
                                      String fieldName, String type) {
        return resolveField(null, loader, key, className, fieldName, type);
    }

    private static Lookup<Field> resolveField(AnchorTable owner, ClassLoader loader,
                                              String key, String className,
                                              String fieldName, String type) {
        Lookup<Class<?>> classLookup = resolveClass(owner, loader, key, className);
        if (classLookup.value == null) {
            return new Lookup<>(null, classLookup.failure, classLookup.error);
        }
        if (fieldName == null || fieldName.isEmpty()) {
            warn(owner, key, Failure.MISSING_KEY, "empty field name", null);
            return new Lookup<>(null, Failure.MISSING_KEY, null);
        }
        Class<?> expected;
        try {
            expected = parseType(type, loader);
        } catch (Throwable t) {
            warn(owner, key, Failure.SIGNATURE_MISMATCH, type, t);
            return new Lookup<>(null, Failure.SIGNATURE_MISMATCH, t);
        }
        Class<?> current = classLookup.value;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (field.getType() != expected) {
                    warn(owner, key, Failure.FIELD_TYPE_MISMATCH,
                            type + " expected " + expected.getName()
                                    + " actual " + field.getType().getName(), null);
                    return new Lookup<>(null, Failure.FIELD_TYPE_MISMATCH, null);
                }
                return new Lookup<>(field, null, null);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                warn(owner, key, Failure.ERROR, fieldName + ":" + type, t);
                return new Lookup<>(null, Failure.ERROR, t);
            }
        }
        warn(owner, key, Failure.FIELD_NOT_FOUND, fieldName + ":" + type, null);
        return new Lookup<>(null, Failure.FIELD_NOT_FOUND, null);
    }

    /**
     * 解析单个 JVM 类型描述符，如 "Ljava/lang/String;" → String.class、"[[I" → int[][].class。
     * 字段锚点的 {@code s} 使用该格式（方法锚点的 {@code s} 以 "(" 开头）。
     */
    public static Class<?> parseType(String descriptor, ClassLoader loader) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.startsWith("(")) {
            throw new IllegalArgumentException("bad type descriptor: " + descriptor);
        }
        return parseParameterTypes("(" + descriptor + ")V", loader)[0];
    }

    /**
     * 解析目标方法：依次校验类、方法名、参数描述符与返回类型，失败时返回具体类别。
     * 所有失败路径都只返回 null（fail-open），由调用方按锚点缺失跳过。
     */
    static Lookup<Method> resolveMethod(ClassLoader loader, String key, String className,
                                        String methodName, String signature) {
        return resolveMethod(null, loader, key, className, methodName, signature);
    }

    private static Lookup<Method> resolveMethod(AnchorTable owner, ClassLoader loader,
                                                String key, String className,
                                                String methodName, String signature) {
        Lookup<Class<?>> classLookup = resolveClass(owner, loader, key, className);
        if (classLookup.value == null) {
            return new Lookup<>(null, classLookup.failure, classLookup.error);
        }
        return resolveMethodOnClass(owner, loader, key, classLookup.value,
                methodName, signature);
    }

    /** 在已加载的类上校验方法名、参数描述符与返回类型。 */
    private static Lookup<Method> resolveMethodOnClass(AnchorTable owner, ClassLoader loader,
                                                       String key, Class<?> cls,
                                                       String methodName, String signature) {
        if (methodName == null || methodName.isEmpty()) {
            warn(owner, key, Failure.MISSING_KEY, "empty method name", null);
            return new Lookup<>(null, Failure.MISSING_KEY, null);
        }
        Class<?>[] params;
        try {
            params = parseParameterTypes(signature, loader);
        } catch (Throwable t) {
            warn(owner, key, Failure.SIGNATURE_MISMATCH, signature, t);
            return new Lookup<>(null, Failure.SIGNATURE_MISMATCH, t);
        }
        Method method;
        try {
            method = cls.getDeclaredMethod(methodName, params);
        } catch (NoSuchMethodException e) {
            warn(owner, key, Failure.METHOD_NOT_FOUND, methodName + signature, e);
            return new Lookup<>(null, Failure.METHOD_NOT_FOUND, e);
        } catch (Throwable t) {
            warn(owner, key, Failure.ERROR, methodName + signature, t);
            return new Lookup<>(null, Failure.ERROR, t);
        }
        Class<?> returnType;
        try {
            returnType = parseReturnType(signature, loader);
        } catch (Throwable t) {
            warn(owner, key, Failure.SIGNATURE_MISMATCH, signature, t);
            return new Lookup<>(null, Failure.SIGNATURE_MISMATCH, t);
        }
        if (method.getReturnType() != returnType) {
            // 同名同参但返回类型已变：混淆方法语义已变，按锚点缺失跳过
            // 而不是照常 hook（返回类型不缓存，下次仍会重试）。
            warn(owner, key, Failure.RETURN_TYPE_MISMATCH,
                    signature + " expected " + returnType.getName()
                            + " actual " + method.getReturnType().getName(), null);
            return new Lookup<>(null, Failure.RETURN_TYPE_MISMATCH, null);
        }
        return new Lookup<>(method, null, null);
    }

    /** setAccessible 失败也按锚点失效 fail-open 返回 false，不向外抛。 */
    private static boolean makeAccessible(AnchorTable owner, String key,
                                         AccessibleObject object) {
        try {
            object.setAccessible(true);
            return true;
        } catch (Throwable t) {
            warn(owner, key, Failure.ERROR, "setAccessible failed", t);
            return false;
        }
    }

    /** 统一 WARN 文案（英文、可 grep）：锚点 key + 失败类别 + 细节 + 异常类型。 */
    static String failureMessage(String key, Failure failure, String detail, Throwable error) {
        StringBuilder sb = new StringBuilder("anchor ").append(key)
                .append(' ').append(failure.label);
        if (detail != null && !detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        if (error != null) {
            sb.append(" [").append(error.getClass().getName()).append(']');
        }
        return sb.toString();
    }

    private void warn(String key, Failure failure, String detail, Throwable error) {
        warn(this, key, failure, detail, error);
    }

    /**
     * 只记录一次完整文案，不打印异常栈：{@link #failureMessage} 已带异常类型，
     * 同一锚点会反复解析失败，重复 WARN 只会刷屏。
     */
    private static void warn(AnchorTable owner, String key, Failure failure,
                             String detail, Throwable error) {
        try {
            String message = failureMessage(key, failure, detail, error);
            if (owner != null && !owner.warned.add(message)) {
                return;
            }
            logSink.warn(TAG, message);
        } catch (Throwable ignored) {
            // 日志实现异常绝不能影响解析返回值：warn 常在 resolve 的 catch 块里调用，
            // 一旦逃逸会让 method()/clazz() 抛异常而不是 fail-open 返回 null。
        }
    }

    /** 仅供 JVM 单测：已记录过的 WARN 文案数量。 */
    int warnedCount() {
        return warned.size();
    }

    /**
     * 解析 JVM 描述符的返回类型，如 "(I)V" → void.class、
     * "(Z)[[Ljava/lang/String;" → String[][].class。
     * 抽成静态方法便于 JVM 单测，不触碰 JSON 与框架。
     */
    public static Class<?> parseReturnType(String signature, ClassLoader loader) {
        if (signature == null || !signature.startsWith("(")) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        int end = signature.indexOf(')');
        if (end < 0 || end + 1 >= signature.length()) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        // 复用参数解析器：把返回类型包成单参描述符，避免维护第二份类型解析。
        return parseParameterTypes("(" + signature.substring(end + 1) + ")V", loader)[0];
    }

    /**
     * 返回类型校验：anchors.json 的 {@code s} 字段包含返回类型，解析结果必须与
     * 实际方法的返回类型一致；不一致（同名同参但语义已变的混淆方法）或描述符
     * 非法都视为锚点失效，调用方按缺失跳过。
     */
    static boolean returnTypeMatches(Method method, String signature, ClassLoader loader) {
        if (method == null) {
            return false;
        }
        try {
            return method.getReturnType() == parseReturnType(signature, loader);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解析 "(Lcom/x;I[Ljava/lang/String;Z)V" 的参数部分为 Class[]。
     * 抽成静态方法便于 JVM 单测，不触碰 JSON 与框架。
     */
    public static Class<?>[] parseParameterTypes(String signature, ClassLoader loader) {
        if (signature == null || !signature.startsWith("(")) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        int end = signature.indexOf(')');
        if (end <= 0) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        String params = signature.substring(1, end);
        List<Class<?>> out = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            int dims = 0;
            while (i < params.length() && params.charAt(i) == '[') {
                dims++;
                i++;
            }
            if (i >= params.length()) {
                throw new IllegalArgumentException("bad signature: " + signature);
            }
            char c = params.charAt(i);
            Class<?> cls;
            switch (c) {
                case 'Z':
                    cls = boolean.class;
                    break;
                case 'B':
                    cls = byte.class;
                    break;
                case 'C':
                    cls = char.class;
                    break;
                case 'S':
                    cls = short.class;
                    break;
                case 'I':
                    cls = int.class;
                    break;
                case 'J':
                    cls = long.class;
                    break;
                case 'F':
                    cls = float.class;
                    break;
                case 'D':
                    cls = double.class;
                    break;
                case 'V':
                    cls = void.class;
                    break;
                case 'L': {
                    int semi = params.indexOf(';', i);
                    if (semi < 0) {
                        throw new IllegalArgumentException("bad signature: " + signature);
                    }
                    String name = params.substring(i + 1, semi).replace('/', '.');
                    i = semi;
                    try {
                        cls = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalArgumentException("class not found: " + name, e);
                    }
                    break;
                }
                default:
                    throw new IllegalArgumentException("bad signature: " + signature);
            }
            for (int d = 0; d < dims; d++) {
                cls = Array.newInstance(cls, 0).getClass();
            }
            out.add(cls);
            i++;
        }
        return out.toArray(new Class<?>[0]);
    }
}
