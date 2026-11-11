package com.mes.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class CodeGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final LocalDate NEVER_BASE = LocalDate.of(2000, 1, 1);

    private final JdbcTemplate jdbcTemplate;

    public CodeGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按业务类型生成单号：优先读取 sys_code_rule 中的自定义规则；
     * 规则缺失或停用时回退为 bizType + yyyyMMdd + 4 位流水。
     */
    public String next(String bizType) {
        LocalDate today = LocalDate.now();
        Rule rule = loadRule(bizType);
        if (rule == null || !rule.enabled()) {
            return fallback(bizType, today);
        }
        String key = bizType + ":" + rule.prefix() + ":" + rule.datePattern() + ":"
                + rule.separator() + ":" + rule.seqLength();
        LocalDate periodDate = periodDate(rule.resetCycle(), today);
        Integer seq = jdbcTemplate.queryForObject("""
                insert into code_seq (prefix, seq_date, seq)
                values (?, ?, 1)
                on conflict (prefix, seq_date)
                do update set seq = code_seq.seq + 1
                returning seq
                """, Integer.class, key, Date.valueOf(periodDate));
        int value = seq == null ? 1 : seq;
        return format(rule.prefix(), rule.datePattern(), rule.separator(), rule.seqLength(), today, value);
    }

    /**
     * 生成示例号，不占用流水（固定使用 1 号流水与当天日期）。
     */
    public String preview(String prefix, String datePattern, String separator, int seqLength) {
        return format(prefix, datePattern, separator, seqLength, LocalDate.now(), 1);
    }

    /**
     * 单号拼接：prefix + 分隔符 + 格式化日期 + 分隔符 + 补零流水；
     * 日期格式为空时省略日期段，分隔符为空时直接拼接。
     */
    public String format(String prefix, String datePattern, String separator, int seqLength,
                         LocalDate date, int seq) {
        String sep = separator == null ? "" : separator;
        StringBuilder code = new StringBuilder(prefix == null ? "" : prefix);
        if (datePattern != null && !datePattern.isBlank()) {
            code.append(sep).append(date.format(DateTimeFormatter.ofPattern(datePattern)));
        }
        code.append(sep).append(String.format("%0" + Math.max(seqLength, 1) + "d", seq));
        return code.toString();
    }

    private String fallback(String bizType, LocalDate today) {
        Integer seq = jdbcTemplate.queryForObject("""
                insert into code_seq (prefix, seq_date, seq)
                values (?, ?, 1)
                on conflict (prefix, seq_date)
                do update set seq = code_seq.seq + 1
                returning seq
                """, Integer.class, bizType, Date.valueOf(today));
        int value = seq == null ? 1 : seq;
        return bizType + today.format(DAY) + String.format("%04d", value);
    }

    private LocalDate periodDate(String resetCycle, LocalDate today) {
        if (resetCycle == null || resetCycle.isBlank()) {
            return today;
        }
        return switch (resetCycle.trim().toUpperCase(Locale.ROOT)) {
            case "MONTH" -> today.withDayOfMonth(1);
            case "YEAR" -> today.withDayOfYear(1);
            case "NEVER" -> NEVER_BASE;
            default -> today;
        };
    }

    private Rule loadRule(String bizType) {
        List<Rule> rules = jdbcTemplate.query("""
                select prefix, date_pattern, separator, seq_length, reset_cycle, enabled
                from sys_code_rule
                where biz_type = ?
                """, (rs, rowNum) -> new Rule(
                rs.getString("prefix"),
                blankToEmpty(rs.getString("date_pattern")),
                blankToEmpty(rs.getString("separator")),
                rs.getInt("seq_length"),
                rs.getString("reset_cycle"),
                rs.getBoolean("enabled")), bizType);
        return rules.isEmpty() ? null : rules.get(0);
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Rule(String prefix, String datePattern, String separator, int seqLength,
                        String resetCycle, boolean enabled) {
    }
}
