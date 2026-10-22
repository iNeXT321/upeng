package com.yt.agent.nl2sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全护栏（问数场景的第一道防线）
 *
 * <p>为什么必须做：NL2SQL 的 SQL 是大模型生成的，本质上是「不可信输入」。一旦直接落到生产库执行，
 * 等于把数据库写权限开放给了一个概率性系统。所以安全校验必须在执行前完成，且必须默认拒绝(白名单)。</p>
 *
 * <p>防御层次（Defense in Depth，任何单层都可能被绕过，必须叠加）：
 * <ol>
 *   <li>预处理：剥离注释（防 {@code /*}{@code *}{@code /} 与 {@code --} 内藏关键字）、合并空白、拆多语句</li>
 *   <li>语句类型白名单：只放行 SELECT / WITH...SELECT / SHOW / DESC / EXPLAIN</li>
 *   <li>危险关键字黑名单：DDL / DML / TCL / DCL / 危险函数（sleep、load_file、copy 等）</li>
 *   <li>表级白名单：只允许访问 Schema 召回结果中已授权的表（数据权限 + 防跨库越权）</li>
 *   <li>系统表防护：禁止 pg_catalog、mysql.user、information_schema 中的敏感视图</li>
 *   <li>强制 LIMIT：无 LIMIT 时自动追加，防全表扫描打爆库和上下文</li>
 *   <li>执行期兜底：只读连接 setReadOnly(true) + setQueryTimeout（见 SqlExecuteTool）</li>
 * </ol>
 * </p>
 *
 * <p>设计取舍：这里用「正则 + 词法切分」而非引入 JSqlParser/Calcite。原因是护栏要做的是
 * <b>拒绝</b>而不是<b>改写</b>，正则足够且零依赖、零解析开销；真正的语义解析交给数据库本身
 * （执行报错会反馈给模型自纠错）。生产环境若要支持 SQL 改写（如自动加租户条件），建议再叠加 Calcite。</p>
 */
@Slf4j
public class SqlSafetyGuard {

    /** 允许的语句起始关键字（白名单，默认拒绝原则） */
    private static final Set<String> ALLOWED_STATEMENT_PREFIX = Set.of(
            "select", "with", "show", "desc", "describe", "explain"
    );

    /**
     * 高危关键字黑名单。使用单词边界匹配，避免误伤列名（如 update_time、delete_flag 是合法列名）。
     * 注意：\b 在 update_time 中不会命中 update，因为 _ 属于单词字符，update_time 是一个完整单词。
     */
    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|truncate|alter|create|rename|replace|merge|"
                    + "grant|revoke|commit|rollback|savepoint|"
                    + "call|exec|execute|handler|load|lock|unlock|shutdown|kill|"
                    + "into\\s+outfile|into\\s+dumpfile|set\\s+global)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 危险函数黑名单：时间盲注、文件读写、系统命令、资源耗尽 */
    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
            "\\b(pg_sleep|sleep|benchmark|waitfor|load_file|lo_import|lo_export|"
                    + "pg_read_file|pg_read_binary_file|pg_ls_dir|dblink|copy|"
                    + "utl_http|utl_file|xp_cmdshell|sp_executesql|information_schema\\.user)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 系统表/敏感元数据访问防护 */
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(pg_catalog|pg_authid|pg_shadow|mysql\\.user|mysql\\.db|performance_schema|"
                    + "sys\\.|sysobjects|sqlite_master|pg_settings)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 多语句检测：分号后还有非空内容即为多语句 */
    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");

    /** 注释块：/* *&#47; ... *&#47;（含跨行）、-- 行注释、# 行注释 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(--|#)[^\\n]*");

    /** LIMIT 子句检测（兼容 MySQL LIMIT n / PG LIMIT n / FETCH FIRST n ROWS ONLY） */
    private static final Pattern HAS_LIMIT = Pattern.compile(
            "\\b(limit\\s+\\d+|fetch\\s+first\\s+\\d+\\s+rows?\\s+only|top\\s+\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 表名提取：from / join 后的标识符，支持 schema.table 与反引号/双引号包裹 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+[`\"]?([a-zA-Z_][\\w$]*)[`\"]?\\s*(?:\\.\\s*[`\"]?([a-zA-Z_][\\w$]*)[`\"]?)?",
            Pattern.CASE_INSENSITIVE
    );

    /** 默认强制 LIMIT，防止模型漏写导致全表返回打爆上下文 */
    private final int defaultRowLimit;

    /** 授权表白名单（为空表示不做表级校验，由调用方决定是否启用） */
    private final Set<String> allowedTables;

    public SqlSafetyGuard() {
        this(1000, Set.of());
    }

    public SqlSafetyGuard(int defaultRowLimit, Set<String> allowedTables) {
        this.defaultRowLimit = defaultRowLimit > 0 ? defaultRowLimit : 1000;
        this.allowedTables = allowedTables == null ? Set.of() : allowedTables;
    }

    /**
     * 校验并规范化 SQL。这是执行前的唯一入口，校验不通过绝不放行。
     *
     * @param rawSql 大模型生成的原始 SQL（不可信输入）
     * @return 校验结果；通过时含规范化并追加 LIMIT 的安全 SQL
     */
    public GuardResult validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardResult.reject("SQL 为空", null);
        }

        // 第 1 步：剥离注释。必须在关键字检测之前做，否则 /*insert*/ 会被当成注释而漏检，
        // 或 select 1 -- ; drop table x 这类注释内藏语句被误判。剥离后若仍含危险关键字才是真危险。
        String stripped = stripComments(rawSql);
        if (stripped.isBlank()) {
            return GuardResult.reject("剥离注释后 SQL 为空，疑似注入载荷", null);
        }

        // 第 2 步：提取 Markdown 代码块包裹的 SQL（模型常输出 ```sql ... ```）
        String sql = extractFromCodeFence(stripped).trim();

        // 第 3 步：多语句检测。分号结尾合法，分号后还有内容则是堆叠注入
        sql = sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
        if (MULTI_STATEMENT.matcher(sql).find()) {
            return GuardResult.reject("检测到多语句(堆叠注入)，只允许执行单条 SELECT 语句", null);
        }

        String lower = sql.toLowerCase(Locale.ROOT);

        // 第 4 步：语句类型白名单
        String prefix = firstWord(lower);
        if (!ALLOWED_STATEMENT_PREFIX.contains(prefix)) {
            return GuardResult.reject(
                    String.format("语句类型 '%s' 不在白名单内，仅允许 %s", prefix,  ALLOWED_STATEMENT_PREFIX), sql);
        }

        // 第 5 步：危险关键字黑名单
        Matcher kw = DANGEROUS_KEYWORDS.matcher(sql);
        if (kw.find()) {
            return GuardResult.reject("检测到高危操作关键字: " + kw.group(1), sql);
        }

        // 第 6 步：危险函数黑名单（时间盲注 / 文件读写 / 资源耗尽）
        Matcher fn = DANGEROUS_FUNCTIONS.matcher(sql);
        if (fn.find()) {
            return GuardResult.reject("检测到危险函数: " + fn.group(1), sql);
        }

        // 第 7 步：系统表访问防护
        Matcher sys = SYSTEM_SCHEMA.matcher(sql);
        if (sys.find()) {
            return GuardResult.reject("禁止访问系统表/敏感元数据: " + sys.group(1), sql);
        }

        // 第 8 步：表级白名单校验（数据权限核心）
        List<String> referenced = extractTables(sql);
        if (!allowedTables.isEmpty() && !referenced.isEmpty()) {
            List<String> denied = referenced.stream()
                    .filter(t -> !isTableAllowed(t))
                    .toList();
            if (!denied.isEmpty()) {
                return GuardResult.reject(
                        "以下表未授权访问: " + denied + "，只能查询已召回的授权表: " + allowedTables, sql);
            }
        }

        // 第 9 步：强制 LIMIT
        String safeSql = enforceLimit(sql);

        return GuardResult.accept(safeSql, referenced);
    }

    /**
     * 剥离 SQL 注释。字符串字面量内的 -- 与 /* 不应被误删，因此按引号状态机扫描。
     */
    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inSingle = false, inDouble = false, inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            // 字符串状态切换（处理 '' 与 \" 转义）
            if (c == '\'' && !inDouble && !inBacktick) {
                if (inSingle && next == '\'') { sb.append(c).append(next); i++; continue; }
                inSingle = !inSingle; sb.append(c); continue;
            }
            if (c == '"' && !inSingle && !inBacktick) {
                inDouble = !inDouble; sb.append(c); continue;
            }
            if (c == '`' && !inSingle && !inDouble) {
                inBacktick = !inBacktick; sb.append(c); continue;
            }

            // 仅在非字符串上下文中剥离注释
            if (!inSingle && !inDouble && !inBacktick) {
                if (c == '/' && next == '*') {                 // 块注释 /* ... */
                    int end = sql.indexOf("*/", i + 2);
                    i = end < 0 ? sql.length() : end + 1;
                    sb.append(' ');
                    continue;
                }
                if ((c == '-' && next == '-') || c == '#') {    // 行注释 -- 或 #
                    int end = sql.indexOf('\n', i);
                    i = end < 0 ? sql.length() : end;
                    sb.append(' ');
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** 从 ```sql ... ``` 代码块中提取 SQL；无代码块则原样返回 */
    private String extractFromCodeFence(String sql) {
        Matcher m = Pattern.compile("```(?:sql)?\\s*(.+?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
        return m.find() ? m.group(1) : sql;
    }

    private String firstWord(String s) {
        String t = s.trim();
        int i = 0;
        while (i < t.length() && Character.isLetterOrDigit(t.charAt(i))) i++;
        return t.substring(0, i);
    }

    /** 提取 from / join 引用的表名（含 schema 前缀形式 schema.table） */
    /**
     * CTE 别名提取：WITH a AS (...), b AS (...) 中的 a/b 是"结果集别名"而非物理表，
     * 不应参与表级授权校验（否则所有合法 CTE 查询都会被误判为"未授权访问"）。
     */
    private static final Pattern CTE_ALIAS = Pattern.compile(
            "(?i)\\bwith\\s+([a-zA-Z_][\\w$]*)\\s+as\\s*\\(|\\)\\s*,\\s*([a-zA-Z_][\\w$]*)\\s+as\\s*\\(");

    public List<String> extractTables(String sql) {
        Set<String> cteAliases = new HashSet<>();
        Matcher cm = CTE_ALIAS.matcher(sql);
        while (cm.find()) {
            String alias = cm.group(1) != null ? cm.group(1) : cm.group(2);
            if (alias != null) {
                cteAliases.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) {
            String first = m.group(1);
            String second = m.group(2);
            // schema.table 形式：group(1)=schema, group(2)=table，记录完整限定名
            String name = second != null ? first + "." + second : first;
            // CTE 别名不是物理表，从授权清单中剔除
            if (second == null && cteAliases.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            tables.add(name);
        }
        return tables.stream().distinct().toList();
    }

    private boolean isTableAllowed(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        if (allowedTables.contains(t)) return true;
        // 兼容只登记了表名而 SQL 写了 schema.table 的情况
        int dot = t.lastIndexOf('.');
        return dot > 0 && allowedTables.contains(t.substring(dot + 1));
    }

    /** 无 LIMIT 时自动追加，避免全表返回 */
    private String enforceLimit(String sql) {
        if (HAS_LIMIT.matcher(sql).find()) {
            return sql;
        }
        // 子查询/CTE 场景下追加在最外层末尾是安全的（LIMIT 作用于最终结果集）
        return sql + " LIMIT " + defaultRowLimit;
    }

    /**
     * 护栏校验结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuardResult {
        /** 是否通过校验 */
        private boolean passed;
        /** 通过后的安全 SQL（已规范化并强制 LIMIT） */
        private String safeSql;
        /** 拒绝原因，会作为 Observation 反馈给模型做自纠错 */
        private String reason;
        /** SQL 中引用到的表，供表级数据权限与 trace 记录 */
        private List<String> referencedTables;

        public static GuardResult accept(String safeSql, List<String> tables) {
            return GuardResult.builder().passed(true).safeSql(safeSql)
                    .referencedTables(tables).build();
        }

        public static GuardResult reject(String reason, String sql) {
            return GuardResult.builder().passed(false).reason(reason).safeSql(sql).build();
        }
    }
}
