package com.mes.finance;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研发费用辅助账：按项目归集人员人工、直接投入、折旧、摊销等费用，
 * 年度汇算的「研发费用加计扣除」直接取本台账金额。
 */
@Service
public class RdProjectService {

    private static final List<String> CATEGORIES =
            List.of("PERSONNEL", "MATERIAL", "DEPRECIATION", "AMORTIZATION", "OTHER");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;

    public RdProjectService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
    }

    public record ProjectRequest(String name, Integer taxYear, String responsible, String remark) {
    }

    public record ExpenseRequest(LocalDate expenseDate, String category, BigDecimal amount, String remark) {
    }

    public PageResult projects(Integer year, String keyword, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1 ");
        if (year != null) {
            where.append(" and p.tax_year = ? ");
            args.add(year);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (p.project_no ilike ? or p.name ilike ? or coalesce(p.responsible, '') ilike ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from rd_project p" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select p.id, p.project_no as "projectNo", p.name, p.tax_year as "taxYear",
                       p.responsible, p.status, p.remark,
                       (select coalesce(sum(e.amount), 0) from rd_expense e
                         where e.project_id = p.id) as "totalExpense",
                       (select count(*) from rd_expense e where e.project_id = p.id) as "expenseCount",
                       p.created_by as "createdBy",
                       to_char(p.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from rd_project p
                """ + where + " order by p.tax_year desc, p.id desc limit ? offset ?",
                pageArgs.toArray());
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public long create(ProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BizException(1002, "请填写研发项目名称");
        }
        if (request.taxYear() == null || request.taxYear() < 2000 || request.taxYear() > 2100) {
            throw new BizException(1002, "请填写正确的研发年度");
        }
        String projectNo = codeGenerator.next("RD");
        Long id = jdbcTemplate.queryForObject("""
                insert into rd_project (project_no, name, tax_year, responsible, remark, created_by)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, projectNo, request.name().trim(), request.taxYear(),
                blankToNull(request.responsible()), blankToNull(request.remark()), CurrentUser.name());
        if (id == null) {
            throw new BizException(1002, "研发项目创建失败");
        }
        return id;
    }

    public PageResult expenses(long projectId, int page, int size) {
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from rd_expense where project_id = ?", Long.class, projectId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, project_id as "projectId", expense_date as "expenseDate",
                       category, amount, remark, created_by as "createdBy",
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from rd_expense where project_id = ?
                order by expense_date desc, id desc limit ? offset ?
                """, projectId, size, (page - 1) * size);
        return new PageResult(rows, total == null ? 0 : total, page, size);
    }

    @Transactional
    public long addExpense(long projectId, ExpenseRequest request) {
        List<Map<String, Object>> projects = jdbcTemplate.queryForList(
                "select id, tax_year as \"taxYear\", status from rd_project where id = ?", projectId);
        if (projects.isEmpty()) {
            throw new BizException(1002, "研发项目不存在");
        }
        Map<String, Object> project = projects.get(0);
        if ("CLOSED".equals(project.get("status"))) {
            throw new BizException(1002, "研发项目已关闭，不能再归集费用");
        }
        if (request.expenseDate() == null) {
            throw new BizException(1002, "请填写费用日期");
        }
        int taxYear = ((Number) project.get("taxYear")).intValue();
        if (request.expenseDate().getYear() != taxYear) {
            throw new BizException(1002, "费用日期必须属于研发年度 " + taxYear);
        }
        String category = request.category() == null ? "" : request.category().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw new BizException(1002, "费用类别不合法");
        }
        BigDecimal amount = request.amount() == null ? BigDecimal.ZERO
                : request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BizException(1002, "归集金额必须大于 0");
        }
        Long id = jdbcTemplate.queryForObject("""
                insert into rd_expense (project_id, expense_date, category, amount, remark, created_by)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, projectId, request.expenseDate(), category, amount,
                blankToNull(request.remark()), CurrentUser.name());
        return id == null ? 0 : id;
    }

    @Transactional
    public void deleteExpense(long expenseId) {
        jdbcTemplate.update("delete from rd_expense where id = ?", expenseId);
    }

    @Transactional
    public void closeProject(long projectId) {
        jdbcTemplate.update("update rd_project set status = 'CLOSED' where id = ?", projectId);
    }

    /** 年度研发费用汇总：按类别 + 合计，供加计扣除和页面展示 */
    public Map<String, Object> summary(int year) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select e.category, coalesce(sum(e.amount), 0) as "amount", count(*) as "count"
                from rd_expense e join rd_project p on p.id = e.project_id
                where p.tax_year = ?
                group by e.category order by e.category
                """, year);
        BigDecimal total = jdbcTemplate.queryForObject("""
                select coalesce(sum(e.amount), 0) from rd_expense e
                join rd_project p on p.id = e.project_id where p.tax_year = ?
                """, BigDecimal.class, year);
        Long projects = jdbcTemplate.queryForObject(
                "select count(*) from rd_project where tax_year = ?", Long.class, year);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("rows", rows);
        result.put("total", total == null ? BigDecimal.ZERO : total);
        result.put("projectCount", projects == null ? 0 : projects);
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
