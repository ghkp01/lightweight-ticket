package com.mes.andon;

import com.mes.andon.api.AndonPort;
import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.inventory.api.InventoryPort;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.production.api.WorkOrderPort;
import com.mes.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 车间异常安灯：呼叫、接单、解决与看板汇总。
 */
@Service
public class AndonService implements AndonPort {

    private static final Set<String> TYPES = Set.of("MATERIAL", "EQUIPMENT", "QUALITY", "OTHER");

    private static final Set<String> STATUSES = Set.of("OPEN", "ACKED", "RESOLVED");

    private static final String SELECT = """
            select a.id, a.call_no as "callNo", a.work_order_id as "workOrderId",
                   a.work_order_no as "workOrderNo", a.operation_name as "operationName",
                   a.type, a.description, a.status, a.caller, a.handler,
                   to_char(a.created_at, 'YYYY-MM-DD HH24:MI:SS') as "createdAt",
                   to_char(a.acked_at, 'YYYY-MM-DD HH24:MI:SS') as "ackedAt",
                   to_char(a.resolved_at, 'YYYY-MM-DD HH24:MI:SS') as "resolvedAt",
                   floor(extract(epoch from (coalesce(a.resolved_at, now()) - a.created_at)) / 60)::int
                       as "elapsedMinutes"
            from andon_call a
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final NoticePort notice;
    private final InventoryPort inventory;
    private final WorkOrderPort workOrder;
    private final MasterDataPort masterData;

    public AndonService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                        NoticePort notice,
                        InventoryPort inventory,
                        WorkOrderPort workOrder,
                        MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.notice = notice;
        this.inventory = inventory;
        this.workOrder = workOrder;
        this.masterData = masterData;
    }

    public record CallRequest(Long workOrderId, String operationName, String type, String description) {
    }

    @Transactional
    public Map<String, Object> create(CallRequest request) {
        if (request == null || request.workOrderId() == null) {
            throw new BizException(1002, "请选择异常工单");
        }
        String type = normalize(request.type());
        if (!TYPES.contains(type)) {
            throw new BizException(1002, "异常类型只能是 MATERIAL、EQUIPMENT、QUALITY 或 OTHER");
        }
        String description = request.description() == null ? "" : request.description().trim();
        if (description.isEmpty()) {
            throw new BizException(1002, "请填写异常描述");
        }
        if (description.length() > 500) {
            throw new BizException(1002, "异常描述不能超过 500 个字符");
        }
        String callNo = codeGenerator.next("AD");
        String workNo = workOrder.workNo(request.workOrderId());
        if (workNo == null) {
            throw new BizException("生产工单不存在");
        }
        String operationName = request.operationName() == null || request.operationName().isBlank()
                ? null : request.operationName().trim();
        Long id = jdbcTemplate.queryForObject("""
                insert into andon_call (call_no, work_order_id, work_order_no, operation_name,
                                        type, description, status, caller)
                values (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                returning id
                """, Long.class, callNo, request.workOrderId(), workNo, operationName, type, description,
                CurrentUser.name());
        // 安灯呼叫进站内通知，计划与管理员不用一直盯着看板
        String typeLabel = switch (type) {
            case "MATERIAL" -> "物料";
            case "EQUIPMENT" -> "设备";
            case "QUALITY" -> "质量";
            default -> "其他";
        };
        notice.pushToRoles(List.of("PLANNER", "ADMIN"), NoticeMessage.of("TASK",
                typeLabel + "异常安灯 " + callNo + "（" + workNo + "）",
                description + "，呼叫 " + CurrentUser.name() + "，请尽快接单处理", "ANDON", callNo));
        return load(id);
    }

    /**
     * 下达工单时如果库存盖不住待领量，自动报一条 MATERIAL 安灯，仓库不用等车间喊人。
     * 只有真的没货才报（料还没领但库里有货不算缺料，领一下就行）。
     * 同一张工单已有未关闭的物料安灯时不再重复报，避免刷屏。
     *
     * @return 真的报了安灯才返回 true
     */
    @Transactional
    @Override
    public boolean raiseMaterialShortage(long workOrderId) {
        String workNo = workOrder.workNo(workOrderId);
        if (workNo == null) {
            return false;
        }
        Long opened = jdbcTemplate.queryForObject("""
                select count(*) from andon_call
                where work_order_id = ? and type = 'MATERIAL' and status <> 'RESOLVED'
                """, Long.class, workOrderId);
        if (opened != null && opened > 0) {
            return false;
        }
        // 待领行由生产侧给（哪几行还没领够是 BOM 口径），结存从库存契约批量取，缺口在本模块比出来
        List<WorkOrderPort.MaterialNeed> needs = workOrder.openMaterialNeeds(List.of(workOrderId));
        if (needs.isEmpty()) {
            return false;
        }
        List<Long> materialIds = needs.stream().map(WorkOrderPort.MaterialNeed::materialId).distinct().toList();
        Map<Long, ProductInfo> products = masterData.products(materialIds);
        Map<Long, BigDecimal> stocks = inventory.totalStocks(materialIds);
        List<Map<String, Object>> shortages = new java.util.ArrayList<>();
        for (WorkOrderPort.MaterialNeed need : needs) {
            BigDecimal stock = stocks.getOrDefault(need.materialId(), BigDecimal.ZERO);
            BigDecimal pending = need.needQty().subtract(stock).setScale(4, java.math.RoundingMode.HALF_UP);
            if (pending.signum() > 0) {
                ProductInfo product = products.get(need.materialId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("materialId", need.materialId());
                item.put("code", product == null ? null : product.code());
                item.put("name", product == null ? null : product.name());
                item.put("unit", product == null ? null : product.unit());
                item.put("needQty", need.needQty());
                item.put("pending", pending);
                shortages.add(item);
            }
        }
        shortages.sort(java.util.Comparator
                .comparing((Map<String, Object> item) -> (BigDecimal) item.get("pending")).reversed()
                .thenComparing(item -> String.valueOf(item.get("code"))));
        long lineCount = shortages.size();
        if (shortages.isEmpty()) {
            return false;
        }
        if (shortages.size() > 5) {
            shortages = new java.util.ArrayList<>(shortages.subList(0, 5));
        }
        StringBuilder description = new StringBuilder("下达时库存不足，缺：");
        for (int i = 0; i < shortages.size(); i++) {
            Map<String, Object> item = shortages.get(i);
            if (i > 0) {
                description.append("、");
            }
            description.append(item.get("code")).append(" ").append(item.get("name"))
                    .append(" 缺 ").append(plain(item.get("pending")))
                    .append(nullToEmpty(item.get("unit")));
        }
        long lines = lineCount;
        if (lines > shortages.size()) {
            description.append(" 等 ").append(lines).append(" 项");
        }
        jdbcTemplate.update("""
                insert into andon_call (call_no, work_order_id, work_order_no, operation_name,
                                        type, description, status, caller)
                values (?, ?, ?, null, 'MATERIAL', ?, 'OPEN', '系统')
                """, codeGenerator.next("AD"), workOrderId,
                workNo, trim(description.toString()));
        return true;
    }

    @Override
    public int resolveMaterialShortage(Collection<Long> workOrderIds) {
        List<Long> ids = workOrderIds == null ? List.of()
                : workOrderIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.update("""
                update andon_call set status = 'RESOLVED', handler = '系统', resolved_at = now()
                where type = 'MATERIAL' and status = 'OPEN'
                  and description like '下达时库存不足%'
                  and work_order_id in (""" + marks + ")", ids.toArray());
    }

    private static String plain(Object value) {
        if (value == null) {
            return "0";
        }
        BigDecimal number = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
        return number.toPlainString();
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String trim(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    public List<Map<String, Object>> list(String status) {
        return list(status, null);
    }

    /** @param keyword 按安灯号 / 工单号 / 异常描述模糊查，消息中心跳转时用来定位 */
    public List<Map<String, Object>> list(String status, String keyword) {
        String filter = normalize(status);
        if ("ALL".equals(filter)) {
            filter = "";
        }
        if (!filter.isEmpty() && !STATUSES.contains(filter)) {
            throw new BizException(1002, "安灯状态只能是 OPEN、ACKED 或 RESOLVED");
        }
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new java.util.ArrayList<>();
        List<String> where = new java.util.ArrayList<>();
        if (!filter.isEmpty()) {
            where.add("a.status = ?");
            args.add(filter);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.add("(a.call_no ilike ? or a.work_order_no ilike ? or a.description ilike ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (!where.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", where));
        }
        sql.append(" order by a.created_at desc, a.id desc");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public Map<String, Object> ack(long id) {
        Map<String, Object> call = load(id);
        if (!"OPEN".equals(String.valueOf(call.get("status")))) {
            throw new BizException(2014, "只有待响应的安灯呼叫可以接单");
        }
        jdbcTemplate.update("""
                update andon_call set status = 'ACKED', handler = ?, acked_at = now()
                where id = ? and status = 'OPEN'
                """, CurrentUser.name(), id);
        return load(id);
    }

    @Transactional
    public Map<String, Object> resolve(long id) {
        Map<String, Object> call = load(id);
        String status = String.valueOf(call.get("status"));
        if (!"OPEN".equals(status) && !"ACKED".equals(status)) {
            throw new BizException(2015, "只有待响应或处理中的安灯呼叫可以解决");
        }
        jdbcTemplate.update("""
                update andon_call set status = 'RESOLVED', handler = ?, resolved_at = now()
                where id = ? and status in ('OPEN', 'ACKED')
                """, CurrentUser.name(), id);
        return load(id);
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("open", count("select count(*) from andon_call where status = 'OPEN'"));
        summary.put("acked", count("select count(*) from andon_call where status = 'ACKED'"));
        summary.put("resolvedToday", count("""
                select count(*) from andon_call
                where status = 'RESOLVED' and resolved_at::date = current_date
                """));
        BigDecimal avgAck = jdbcTemplate.queryForObject("""
                select coalesce(round(avg(extract(epoch from (acked_at - created_at)) / 60.0), 1), 0)
                from andon_call where acked_at is not null
                """, BigDecimal.class);
        summary.put("avgAckMinutes", avgAck == null ? BigDecimal.ZERO : avgAck);
        return summary;
    }

    private Map<String, Object> load(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT + " where a.id = ?", id);
        if (rows.isEmpty()) {
            throw new BizException("安灯呼叫不存在");
        }
        return rows.get(0);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
