package com.mes.finance;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 分支机构所得税分摊：各分支机构维护分摊比例，总机构为剩余比例。
 */
@Service
public class TaxBranchService {

    private final JdbcTemplate jdbcTemplate;

    public TaxBranchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record BranchRequest(Long id, String name, String taxpayerId,
                                BigDecimal shareRatio, Boolean enabled, String remark) {
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                select id, name, taxpayer_id as "taxpayerId", share_ratio as "shareRatio",
                       enabled, remark
                from tax_branch order by id
                """);
    }

    @Transactional
    public long save(BranchRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "请填写分支机构名称");
        }
        BigDecimal ratio = request.shareRatio() == null ? BigDecimal.ZERO
                : request.shareRatio().setScale(6, RoundingMode.HALF_UP);
        if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "分摊比例必须在 0~1 之间");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        BigDecimal used = jdbcTemplate.queryForObject("""
                select coalesce(sum(share_ratio), 0) from tax_branch
                where enabled and (?::bigint is null or id <> ?::bigint)
                """, BigDecimal.class, request.id(), request.id());
        BigDecimal total = (used == null ? BigDecimal.ZERO : used).add(ratio);
        if (enabled && total.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "分支机构分摊比例合计不能超过 100%，当前合计 " + total);
        }
        if (request.id() == null) {
            Long id = jdbcTemplate.queryForObject("""
                    insert into tax_branch (name, taxpayer_id, share_ratio, enabled, remark)
                    values (?, ?, ?, ?, ?) returning id
                    """, Long.class, request.name().trim(), blankToNull(request.taxpayerId()),
                    ratio, enabled, blankToNull(request.remark()));
            return id == null ? 0 : id;
        }
        jdbcTemplate.update("""
                update tax_branch set name = ?, taxpayer_id = ?, share_ratio = ?, enabled = ?, remark = ?
                where id = ?
                """, request.name().trim(), blankToNull(request.taxpayerId()), ratio, enabled,
                blankToNull(request.remark()), request.id());
        return request.id();
    }

    @Transactional
    public void delete(long id) {
        jdbcTemplate.update("delete from tax_branch where id = ?", id);
    }

    public List<Map<String, Object>> enabledBranches() {
        return jdbcTemplate.queryForList("""
                select id, name, share_ratio as "shareRatio"
                from tax_branch where enabled order by id
                """);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
