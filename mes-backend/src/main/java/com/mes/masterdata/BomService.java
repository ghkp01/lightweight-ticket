package com.mes.masterdata;

import com.mes.approval.api.ApprovalPort;
import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.BomLineInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BOM（物料清单）标准对象：BOM 头负责版本、状态、生效日期与基础数量，行明细挂 bom_id。
 *
 * <p>一个物料可以维护多套 BOM；同一时刻只允许一套 ACTIVE，工单与 MRP 按
 * 「ACTIVE + 当天在生效区间内」取料。历史数据在迁移时自动补成 V1.0 生效版本。</p>
 */
@Service
public class BomService {

    private static final int MAX_VERSION_LENGTH = 32;

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final ApprovalPort approval;

    public BomService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator, ApprovalPort approval) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.approval = approval;
    }

    public record BomHeaderRequest(String version, BigDecimal baseQty, LocalDate effectiveFrom,
                                   LocalDate effectiveTo, String status, String remark) {
    }

    public record BomLineRequest(Long materialId, BigDecimal qty, Integer operationSeq,
                                 String substituteGroup, Boolean isKey, String remark,
                                 Long substituteMaterialId) {
    }

    // ---------------------------------------------------------------- 查询

    public List<Map<String, Object>> list(String keyword, String status) {
        return queryBoms(keyword, status, null, null);
    }

    /** 分页列表：BOM 版本会随审批不断累积，列表改为后端分页避免一次拉全表 */
    public PageResult page(String keyword, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        List<Object> countArgs = new ArrayList<>();
        StringBuilder where = bomWhere(keyword, status, countArgs);
        Long total = jdbcTemplate.queryForObject("""
                select count(*) from bom b join product p on p.id = b.product_id
                """ + where, Long.class, countArgs.toArray());
        List<Map<String, Object>> records =
                queryBoms(keyword, status, safeSize, (safePage - 1) * safeSize);
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private StringBuilder bomWhere(String keyword, String status, List<Object> args) {
        StringBuilder sql = new StringBuilder(" where 1 = 1 ");
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (p.code ilike ? or p.name ilike ? or b.bom_no ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and b.status = ? ");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        return sql;
    }

    private List<Map<String, Object>> queryBoms(String keyword, String status, Integer limit, Integer offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select b.id, b.bom_no as "bomNo", b.product_id as "productId",
                       p.code as "productCode", p.name as "productName", p.category, p.unit,
                       b.version, b.status, b.base_qty as "baseQty",
                       to_char(b.effective_from, 'YYYY-MM-DD') as "effectiveFrom",
                       to_char(b.effective_to, 'YYYY-MM-DD') as "effectiveTo",
                       b.remark, to_char(b.updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt",
                       (select count(*) from bom_item i where i.bom_id = b.id) as "lineCount"
                from bom b
                join product p on p.id = b.product_id
                """);
        sql.append(bomWhere(keyword, status, args));
        sql.append(" order by b.product_id, b.id desc ");
        if (limit != null) {
            sql.append(" limit ? offset ?");
            args.add(limit);
            args.add(offset);
        }
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> detail(long bomId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select b.id, b.bom_no as "bomNo", b.product_id as "productId",
                       p.code as "productCode", p.name as "productName", p.category, p.unit,
                       b.version, b.status, b.base_qty as "baseQty",
                       to_char(b.effective_from, 'YYYY-MM-DD') as "effectiveFrom",
                       to_char(b.effective_to, 'YYYY-MM-DD') as "effectiveTo",
                       b.remark, to_char(b.updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt"
                from bom b
                join product p on p.id = b.product_id
                where b.id = ?
                """, bomId);
        if (rows.isEmpty()) {
            throw new BizException(2004, "BOM 不存在");
        }
        Map<String, Object> header = new LinkedHashMap<>(rows.get(0));
        header.put("lines", lines(bomId));
        return header;
    }

    public List<Map<String, Object>> lines(long bomId) {
        return jdbcTemplate.queryForList("""
                select i.id, i.bom_id as "bomId", i.material_id as "materialId",
                       m.code as "materialCode", m.name as "materialName", m.spec, m.unit,
                       i.qty, i.operation_seq as "operationSeq",
                       i.substitute_group as "substituteGroup", i.is_key as "isKey", i.remark,
                       i.substitute_material_id as "substituteMaterialId",
                       sm.code as "substituteMaterialCode", sm.name as "substituteMaterialName"
                from bom_item i
                join product m on m.id = i.material_id
                left join product sm on sm.id = i.substitute_material_id
                where i.bom_id = ?
                order by i.operation_seq nulls last, i.id
                """, bomId);
    }

    /** 产品页快捷 BOM 抽屉：返回当前生效 BOM 的行；没有生效 BOM 返回空 */
    public List<Map<String, Object>> activeLinesOfProduct(long productId) {
        Long bomId = activeBomId(productId, LocalDate.now());
        return bomId == null ? List.of() : lines(bomId);
    }

    // ---------------------------------------------------------------- 头维护

    @Transactional
    public long create(long productId, BomHeaderRequest request) {
        assertProduct(productId);
        String version = normalizeVersion(request.version());
        BigDecimal baseQty = normalizeBaseQty(request.baseQty());
        LocalDate from = request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom();
        LocalDate to = request.effectiveTo();
        validateDates(from, to);
        assertVersionUnique(productId, version, null);
        // 新版本一律从草稿开始，必须提交审批通过后才生效
        String bomNo = codeGenerator.next("BOM");
        Long id = jdbcTemplate.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty,
                                 effective_from, effective_to, remark)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                returning id
                """, Long.class, bomNo, productId, version, baseQty, from, to,
                trim(request.remark()));
        return id == null ? 0 : id;
    }

    @Transactional
    public void update(long bomId, BomHeaderRequest request) {
        Map<String, Object> header = loadHeader(bomId);
        requireStatus(header, "DRAFT", "只有草稿版本可以修改，生效或审批中的版本请复制为新版本");
        long productId = ((Number) header.get("product_id")).longValue();
        String version = normalizeVersion(request.version());
        BigDecimal baseQty = normalizeBaseQty(request.baseQty());
        LocalDate from = request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom();
        LocalDate to = request.effectiveTo();
        validateDates(from, to);
        assertVersionUnique(productId, version, bomId);
        jdbcTemplate.update("""
                update bom set version = ?, base_qty = ?, effective_from = ?,
                               effective_to = ?, remark = ?, updated_at = now()
                where id = ?
                """, version, baseQty, from, to, trim(request.remark()), bomId);
    }

    /**
     * 提交审批：有审批流时停在 PENDING；业务类型被设为免审批或没有可用流程时直接生效。
     *
     * @return approved=true 表示已直接生效，false 表示已进入审批
     */
    @Transactional
    public Map<String, Object> submit(long bomId) {
        Map<String, Object> header = loadHeader(bomId);
        requireStatus(header, "DRAFT", "只有草稿版本可以提交审批");
        Integer lineCount = jdbcTemplate.queryForObject(
                "select count(*) from bom_item where bom_id = ?", Integer.class, bomId);
        if (lineCount == null || lineCount == 0) {
            throw new BizException(1002, "请先维护 BOM 明细再提交");
        }
        long productId = ((Number) header.get("product_id")).longValue();
        String productCode = jdbcTemplate.queryForObject(
                "select code from product where id = ?", String.class, productId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("productId", productId);
        context.put("productCode", productCode);
        context.put("version", header.get("version"));
        context.put("lineCount", lineCount);
        Long instanceId = approval.submit("BOM", bomId, String.valueOf(header.get("bom_no")),
                "BOM 变更审批 · " + productCode + " " + header.get("version"), context);
        if (instanceId == null) {
            // 没开审批流：按「无需审批」直接生效
            activateNow(bomId, productId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("approved", true);
            result.put("instanceId", null);
            return result;
        }
        jdbcTemplate.update("""
                update bom set status = 'PENDING', approval_instance_id = ?, updated_at = now()
                where id = ?
                """, instanceId, bomId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approved", false);
        result.put("instanceId", instanceId);
        return result;
    }

    /** 撤回在途审批，版本退回草稿 */
    @Transactional
    public void withdraw(long bomId) {
        Map<String, Object> header = loadHeader(bomId);
        requireStatus(header, "PENDING", "只有审批中的版本可以撤回");
        Object instanceId = header.get("approval_instance_id");
        if (instanceId != null) {
            approval.cancel(((Number) instanceId).longValue());
        }
        jdbcTemplate.update("""
                update bom set status = 'DRAFT', approval_instance_id = null, updated_at = now()
                where id = ?
                """, bomId);
    }

    /** 复制成新版本（草稿）：复制头信息与全部明细，版本号不填时自动生成 */
    @Transactional
    public long copyVersion(long sourceId, String newVersion) {
        Map<String, Object> source = loadHeader(sourceId);
        long productId = ((Number) source.get("product_id")).longValue();
        String version = newVersion == null || newVersion.isBlank()
                ? suggestVersion(productId) : normalizeVersion(newVersion);
        assertVersionUnique(productId, version, null);
        BigDecimal baseQty = new BigDecimal(String.valueOf(source.get("base_qty")));
        Long id = jdbcTemplate.queryForObject("""
                insert into bom (bom_no, product_id, version, status, base_qty,
                                 effective_from, effective_to, remark)
                values (?, ?, ?, 'DRAFT', ?, current_date, null, ?)
                returning id
                """, Long.class, codeGenerator.next("BOM"), productId, version, baseQty,
                "复制自 " + source.get("version"));
        jdbcTemplate.update("""
                insert into bom_item (bom_id, material_id, qty, operation_seq, substitute_group,
                                      is_key, remark, substitute_material_id)
                select ?, material_id, qty, operation_seq, substitute_group, is_key, remark,
                       substitute_material_id
                from bom_item where bom_id = ?
                """, id, sourceId);
        return id == null ? 0 : id;
    }

    /** 只有草稿可以删除；生效/审批中/已停用的版本都保留历史 */
    @Transactional
    public void delete(long bomId) {
        Map<String, Object> header = loadHeader(bomId);
        requireStatus(header, "DRAFT", "只有草稿版本可以删除");
        jdbcTemplate.update("delete from bom where id = ?", bomId);
    }

    // ---------------------------------------------------------------- 行维护

    @Transactional
    public void saveLines(long bomId, List<BomLineRequest> request) {
        Map<String, Object> header = loadHeader(bomId);
        requireStatus(header, "DRAFT", "只有草稿版本可以修改明细，生效或审批中的版本请复制为新版本");
        long productId = ((Number) header.get("product_id")).longValue();
        jdbcTemplate.update("delete from bom_item where bom_id = ?", bomId);
        if (request == null) {
            return;
        }
        Set<Long> materials = new HashSet<>();
        for (BomLineRequest line : request) {
            if (line == null || line.materialId() == null) {
                throw new BizException(1002, "请为每一行选择物料");
            }
            if (line.materialId() == productId) {
                throw new BizException(1002, "BOM 不能引用产品自身");
            }
            if (!materials.add(line.materialId())) {
                throw new BizException(1002, "同一物料不能重复出现");
            }
            BigDecimal qty = line.qty();
            if (qty == null || qty.signum() <= 0) {
                throw new BizException(1002, "BOM 用量必须大于 0");
            }
            if (line.substituteMaterialId() != null
                    && line.substituteMaterialId().equals(line.materialId())) {
                throw new BizException(1002, "替代料不能与当前物料相同");
            }
            jdbcTemplate.update("""
                    insert into bom_item (bom_id, material_id, qty, operation_seq,
                                          substitute_group, is_key, remark, substitute_material_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, bomId, line.materialId(), qty,
                    line.operationSeq() == null ? 1 : line.operationSeq(),
                    trim(line.substituteGroup()), Boolean.TRUE.equals(line.isKey()),
                    trim(line.remark()), line.substituteMaterialId());
        }
        jdbcTemplate.update("update bom set updated_at = now() where id = ?", bomId);
    }

    /**
     * 数据导入使用的草稿版本：已有生效 BOM 时不允许直接改，必须先在 BOM 管理里复制新版本。
     * 没有草稿时自动建一个，导入不绕过审批。
     */
    @Transactional
    public long draftBomForImport(long productId) {
        assertProduct(productId);
        List<Long> active = jdbcTemplate.queryForList("""
                select id from bom where product_id = ? and status = 'ACTIVE'
                order by id desc limit 1
                """, Long.class, productId);
        if (!active.isEmpty()) {
            throw new BizException(2004, "该父件已有生效 BOM，请先在 BOM 管理里复制新版本，再导入明细");
        }
        List<Long> drafts = jdbcTemplate.queryForList("""
                select id from bom where product_id = ? and status = 'DRAFT'
                order by id desc limit 1
                """, Long.class, productId);
        if (!drafts.isEmpty()) {
            return drafts.get(0);
        }
        return create(productId, new BomHeaderRequest(suggestVersion(productId), BigDecimal.ONE,
                LocalDate.now(), null, "DRAFT", "导入自动创建"));
    }

    // ---------------------------------------------------------------- 取料口径

    /** 当天生效的 BOM id；没有则返回 null（标准口径：未来版本不会提前生效） */
    public Long activeBomId(long productId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<Long> ids = jdbcTemplate.queryForList("""
                select id from bom
                where product_id = ? and status = 'ACTIVE'
                  and effective_from <= ?
                  and (effective_to is null or effective_to >= ?)
                order by effective_from desc, id desc
                limit 1
                """, Long.class, productId, day, day);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 工单与 MRP 取料：当前生效 BOM 的行，用量已按基础数量折算成「每个父件用量」。
     * 替代料行保留 substituteMaterialId，由调用方决定是否单独建替代行。
     */
    public List<BomLineInfo> activeLines(long productId) {
        Long bomId = activeBomId(productId, LocalDate.now());
        if (bomId == null) {
            return List.of();
        }
        Map<String, Object> header = loadHeader(bomId);
        BigDecimal baseQty = new BigDecimal(String.valueOf(header.get("base_qty")));
        if (baseQty.signum() <= 0) {
            baseQty = BigDecimal.ONE;
        }
        BigDecimal divisor = baseQty;
        return jdbcTemplate.query("""
                select id, material_id, qty, operation_seq, substitute_group, is_key, remark,
                       substitute_material_id
                from bom_item
                where bom_id = ?
                order by operation_seq nulls last, id
                """, (rs, rowNum) -> new BomLineInfo(
                rs.getLong("id"),
                rs.getLong("material_id"),
                rs.getBigDecimal("qty").divide(divisor, 6, RoundingMode.HALF_UP),
                rs.getObject("operation_seq") == null ? null : rs.getInt("operation_seq"),
                rs.getString("substitute_group"),
                rs.getBoolean("is_key"),
                rs.getString("remark"),
                rs.getObject("substitute_material_id") == null
                        ? null : rs.getLong("substitute_material_id")), bomId);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> loadHeader(long bomId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, bom_no, product_id, version, status, base_qty, approval_instance_id
                from bom where id = ?
                """, bomId);
        if (rows.isEmpty()) {
            throw new BizException(2004, "BOM 不存在");
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> header, String expected, String message) {
        if (!expected.equals(String.valueOf(header.get("status")))) {
            throw new BizException(2004, message);
        }
    }

    /** 审批通过或免审批时启用：同物料旧版本自动降级 */
    private void activateNow(long bomId, long productId) {
        deactivateOthers(productId, bomId);
        jdbcTemplate.update("""
                update bom set status = 'ACTIVE', approval_instance_id = null, updated_at = now()
                where id = ?
                """, bomId);
    }

    /** 生成没被占用的版本号：V1.0 / V2.0 … */
    private String suggestVersion(long productId) {
        int index = 1;
        while (true) {
            String version = "V" + index + ".0";
            if (!versionExists(productId, version)) {
                return version;
            }
            index++;
        }
    }

    private void assertProduct(long productId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from product where id = ?", Integer.class, productId);
        if (count == null || count == 0) {
            throw new BizException(1002, "物料不存在");
        }
    }

    private void assertVersionUnique(long productId, String version, Long excludeId) {
        String sql = "select count(*) from bom where product_id = ? and version = ?"
                + (excludeId == null ? "" : " and id <> ?");
        Integer count = excludeId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class, productId, version)
                : jdbcTemplate.queryForObject(sql, Integer.class, productId, version, excludeId);
        if (count != null && count > 0) {
            throw new BizException(2004, "该物料已存在版本 " + version);
        }
    }

    private boolean versionExists(long productId, String version) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from bom where product_id = ? and version = ?", Integer.class,
                productId, version);
        return count != null && count > 0;
    }

    private void deactivateOthers(long productId, Long keepId) {
        if (keepId == null) {
            jdbcTemplate.update("""
                    update bom set status = 'INACTIVE', updated_at = now()
                    where product_id = ? and status = 'ACTIVE'
                    """, productId);
            return;
        }
        jdbcTemplate.update("""
                update bom set status = 'INACTIVE', updated_at = now()
                where product_id = ? and status = 'ACTIVE' and id <> ?
                """, productId, keepId);
    }

    private String normalizeVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(1002, "请填写 BOM 版本");
        }
        String version = value.trim();
        if (version.length() > MAX_VERSION_LENGTH) {
            throw new BizException(1002, "BOM 版本不能超过 " + MAX_VERSION_LENGTH + " 个字符");
        }
        return version;
    }

    private BigDecimal normalizeBaseQty(BigDecimal value) {
        BigDecimal baseQty = value == null ? BigDecimal.ONE : value;
        if (baseQty.signum() <= 0) {
            throw new BizException(1002, "基础数量必须大于 0");
        }
        return baseQty;
    }

    private void validateDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new BizException(1002, "失效日期不能早于生效日期");
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
