package com.mes.quality;

import com.mes.common.BizException;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.process.api.ProcessPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检验项（SIP 的可执行版本）：按产品维护通用项，按工序维护该道工序的检查项。
 * 检验单创建时按「产品 + 工序」取模板，逐项录入结果。
 */
@Service
public class InspectionItemService {

    public record ItemRequest(Long id, String itemCode, String itemName, String standard, String method,
                              String valueType, BigDecimal lowerLimit, BigDecimal upperLimit, String unit,
                              Boolean required, Integer sortNo, Boolean enabled) {
    }

    public record ItemResultRequest(Long itemId, String itemName, String standard, BigDecimal sampleQty,
                                    BigDecimal defectQty, String measured, String result, String remark) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MasterDataPort masterData;
    private final ProcessPort processPort;

    public InspectionItemService(JdbcTemplate jdbcTemplate, MasterDataPort masterData,
                                 ProcessPort processPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
        this.processPort = processPort;
    }

    public List<Map<String, Object>> list(Long productId, Long processId) {
        StringBuilder sql = new StringBuilder("""
                select i.id, i.product_id as "productId", i.process_id as "processId",
                       i.item_code as "itemCode", i.item_name as "itemName", i.standard, i.method,
                       i.value_type as "valueType", i.lower_limit as "lowerLimit",
                       i.upper_limit as "upperLimit", i.unit, i.required, i.sort_no as "sortNo",
                       i.enabled
                from inspection_item i
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (productId != null) {
            sql.append(" and i.product_id = ? ");
            args.add(productId);
        }
        if (processId != null) {
            sql.append(" and i.process_id = ? ");
            args.add(processId);
        }
        sql.append(" order by i.sort_no, i.id ");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        // 产品与工序的名称各自问自己的模块，不再 join 别人的表
        MasterDataViews.fillProductNames(masterData, rows);
        fillProcessNames(rows);
        return rows;
    }

    /** 补工序名称：工序属于 process 模块，按 id 批量问一次 */
    private void fillProcessNames(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(row -> row.get("processId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = processPort.names(ids);
        for (Map<String, Object> row : rows) {
            Object id = row.get("processId");
            if (id != null) {
                row.put("processName", names.get(((Number) id).longValue()));
            }
        }
    }

    /** 全量覆盖某个范围的检验项 */
    @Transactional
    public List<Map<String, Object>> save(Long productId, Long processId, List<ItemRequest> items) {
        if (productId == null && processId == null) {
            throw new BizException(1002, "请指定检验项挂在哪：产品还是工序");
        }
        if (productId != null && processId != null) {
            throw new BizException(1002, "一条检验项只能挂产品或者工序，不能同时挂");
        }
        if (productId != null) {
            // 历史检验单里引用过这些项，先断开引用再删，明细本身保留（名称与标准已快照）
            jdbcTemplate.update("""
                    update inspection_item_result set item_id = null
                    where item_id in (select id from inspection_item where product_id = ?)
                    """, productId);
            jdbcTemplate.update("delete from inspection_item where product_id = ?", productId);
        } else {
            jdbcTemplate.update("""
                    update inspection_item_result set item_id = null
                    where item_id in (select id from inspection_item where process_id = ?)
                    """, processId);
            jdbcTemplate.update("delete from inspection_item where process_id = ?", processId);
        }
        int index = 0;
        for (ItemRequest item : items == null ? List.<ItemRequest>of() : items) {
            if (item.itemName() == null || item.itemName().isBlank()) {
                throw new BizException(1002, "检验项名称不能为空");
            }
            String valueType = item.valueType() == null ? "COUNT" : item.valueType().trim().toUpperCase();
            if (!List.of("COUNT", "MEASURE", "VISUAL").contains(valueType)) {
                throw new BizException(1002, "检验项类型只能是计数、计量或目视");
            }
            if ("MEASURE".equals(valueType) && item.lowerLimit() != null && item.upperLimit() != null
                    && item.lowerLimit().compareTo(item.upperLimit()) > 0) {
                throw new BizException(1002, "检验项「" + item.itemName() + "」的下限不能大于上限");
            }
            index++;
            jdbcTemplate.update("""
                    insert into inspection_item (product_id, process_id, item_code, item_name, standard,
                                                 method, value_type, lower_limit, upper_limit, unit,
                                                 required, sort_no, enabled)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, productId, processId, item.itemCode(), item.itemName().trim(), item.standard(),
                    item.method(), valueType, item.lowerLimit(), item.upperLimit(), item.unit(),
                    item.required() == null || item.required(),
                    item.sortNo() == null ? index : item.sortNo(),
                    item.enabled() == null || item.enabled());
        }
        return list(productId, processId);
    }

    /** 检验单要用的模板：工序项在前、产品通用项在后 */
    public List<Map<String, Object>> resolve(Long productId, Long processId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select i.id, i.product_id as "productId", i.process_id as "processId",
                       i.item_code as "itemCode", i.item_name as "itemName", i.standard, i.method,
                       i.value_type as "valueType", i.lower_limit as "lowerLimit",
                       i.upper_limit as "upperLimit", i.unit, i.required, i.sort_no as "sortNo"
                from inspection_item i
                where i.enabled = true and (
                """);
        boolean hasProcess = processId != null;
        boolean hasProduct = productId != null;
        if (!hasProcess && !hasProduct) {
            return List.of();
        }
        if (hasProcess) {
            sql.append(" i.process_id = ? ");
            args.add(processId);
        }
        if (hasProcess && hasProduct) {
            sql.append(" or ");
        }
        if (hasProduct) {
            sql.append(" i.product_id = ? ");
            args.add(productId);
        }
        sql.append(" ) order by case when i.process_id is not null then 0 else 1 end, i.sort_no, i.id ");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public void writeResults(long inspectionId, List<ItemResultRequest> items) {
        int index = 0;
        for (ItemResultRequest item : items == null ? List.<ItemResultRequest>of() : items) {
            index++;
            String result = item.result() == null ? "" : item.result().trim().toUpperCase();
            BigDecimal defect = item.defectQty() == null ? BigDecimal.ZERO : item.defectQty();
            if (!List.of("PASS", "FAIL").contains(result)) {
                result = defect.signum() > 0 ? "FAIL" : "PASS";
            }
            String name = item.itemName();
            if ((name == null || name.isBlank()) && item.itemId() != null) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "select item_name from inspection_item where id = ?", item.itemId());
                name = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("item_name"));
            }
            if (name == null || name.isBlank()) {
                throw new BizException(1002, "检验项缺少名称");
            }
            jdbcTemplate.update("""
                    insert into inspection_item_result (inspection_id, item_id, item_name, standard,
                                                        sample_qty, defect_qty, measured, result, remark, sort_no)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, inspectionId, item.itemId(), name.trim(), item.standard(),
                    item.sampleQty(), defect, item.measured(), result, item.remark(), index);
        }
    }

    public List<Map<String, Object>> resultsOf(long inspectionId) {
        return jdbcTemplate.queryForList("""
                select r.id, r.item_id as "itemId", r.item_name as "itemName", r.standard,
                       r.sample_qty as "sampleQty", r.defect_qty as "defectQty", r.measured,
                       r.result, r.remark, r.sort_no as "sortNo"
                from inspection_item_result r
                where r.inspection_id = ?
                order by r.sort_no, r.id
                """, inspectionId);
    }
}
