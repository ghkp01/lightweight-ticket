package com.mes.system;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CodeRuleService {

    /** 新建主数据时可自动取号的数据类型 */
    private static final Set<String> MASTER_DATA_TYPES = Set.of("WC", "OP", "WH", "PD", "CUS", "SUP");

    private static final Set<String> RESET_CYCLES = Set.of("DAY", "MONTH", "YEAR", "NEVER");

    private static final Set<String> DATE_PATTERNS = Set.of("yyyyMMdd", "yyyyMM", "yyyy", "");

    private static final int MIN_SEQ_LENGTH = 3;
    private static final int MAX_SEQ_LENGTH = 8;
    private static final int MAX_PREFIX_LENGTH = 16;
    private static final int MAX_SEPARATOR_LENGTH = 4;

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;

    public CodeRuleService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
    }

    public record RuleRequest(String prefix,
                              String datePattern,
                              String separator,
                              Integer seqLength,
                              String resetCycle,
                              Boolean enabled) {
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                select id, biz_type as "bizType", biz_name as "bizName", prefix,
                       date_pattern as "datePattern", separator, seq_length as "seqLength",
                       reset_cycle as "resetCycle", enabled
                from sys_code_rule
                order by id
                """);
        fillSamples(rules);
        return rules;
    }

    /** 编码规则列表分页；不传 page 时仍走 list() 返回全量 */
    public PageResult page(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Long total = jdbcTemplate.queryForObject("select count(*) from sys_code_rule", Long.class);
        List<Map<String, Object>> rules = jdbcTemplate.queryForList("""
                select id, biz_type as "bizType", biz_name as "bizName", prefix,
                       date_pattern as "datePattern", separator, seq_length as "seqLength",
                       reset_cycle as "resetCycle", enabled
                from sys_code_rule
                order by id limit ? offset ?
                """, safeSize, (long) (safePage - 1) * safeSize);
        fillSamples(rules);
        return new PageResult(rules, total == null ? 0 : total, safePage, safeSize);
    }

    private void fillSamples(List<Map<String, Object>> rules) {
        for (Map<String, Object> rule : rules) {
            String prefix = asText(rule.get("prefix"));
            String datePattern = asText(rule.get("datePattern"));
            String separator = asText(rule.get("separator"));
            int seqLength = rule.get("seqLength") instanceof Number number ? number.intValue() : 4;
            rule.put("sample", codeGenerator.preview(prefix, datePattern, separator, seqLength));
        }
    }

    /**
     * 取一个主数据编码：按编码规则生成并占用流水号，供新建弹窗预填。
     */
    public String next(String bizType) {
        String type = bizType == null ? "" : bizType.trim().toUpperCase(Locale.ROOT);
        if (!MASTER_DATA_TYPES.contains(type)) {
            throw new BizException(1002, "不支持的编码类型：" + bizType);
        }
        return codeGenerator.next(type);
    }

    @Transactional
    public void update(long id, RuleRequest request) {
        load(id);
        String prefix = normalizePrefix(request.prefix());
        String datePattern = normalizeDatePattern(request.datePattern());
        String separator = normalizeSeparator(request.separator());
        int seqLength = normalizeSeqLength(request.seqLength());
        String resetCycle = normalizeResetCycle(request.resetCycle());
        boolean enabled = request.enabled() == null || request.enabled();
        jdbcTemplate.update("""
                update sys_code_rule
                set prefix = ?, date_pattern = ?, separator = ?, seq_length = ?,
                    reset_cycle = ?, enabled = ?, updated_at = now()
                where id = ?
                """, prefix, datePattern, separator, seqLength, resetCycle, enabled, id);
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, biz_type as \"bizType\" from sys_code_rule where id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException(4004, "编码规则不存在");
        }
        return rows.get(0);
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new BizException(1002, "前缀不能为空");
        }
        String value = prefix.trim();
        if (value.length() > MAX_PREFIX_LENGTH) {
            throw new BizException(1002, "前缀长度不能超过 16 位");
        }
        return value;
    }

    private String normalizeDatePattern(String datePattern) {
        String value = datePattern == null ? "" : datePattern.trim();
        if (!DATE_PATTERNS.contains(value)) {
            throw new BizException(1002, "日期格式只能是 yyyyMMdd、yyyyMM、yyyy 或留空");
        }
        return value;
    }

    private String normalizeSeparator(String separator) {
        String value = separator == null ? "" : separator.trim();
        if (value.length() > MAX_SEPARATOR_LENGTH) {
            throw new BizException(1002, "分隔符最多 4 位");
        }
        return value;
    }

    private int normalizeSeqLength(Integer seqLength) {
        int value = seqLength == null ? 4 : seqLength;
        if (value < MIN_SEQ_LENGTH || value > MAX_SEQ_LENGTH) {
            throw new BizException(1002, "流水位数只能是 3-8 位");
        }
        return value;
    }

    private String normalizeResetCycle(String resetCycle) {
        String value = resetCycle == null ? "" : resetCycle.trim().toUpperCase(Locale.ROOT);
        if (!RESET_CYCLES.contains(value)) {
            throw new BizException(1002, "重置周期只能是 DAY、MONTH、YEAR 或 NEVER");
        }
        return value;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
