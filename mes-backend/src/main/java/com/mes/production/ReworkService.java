package com.mes.production;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.production.api.ReworkPort;
import com.mes.production.api.ReworkRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 返修工单：完工检不合格、报工不良处置选了返修、退货选了返修时统一走这里。
 * 必须补齐工序（带 process_id 与工价）与 BOM 快照，否则返修单既排不了产也领不了料。
 */
@Service
public class ReworkService implements ReworkPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final ProductionService productionService;
    private final NoticePort notice;
    private final com.mes.masterdata.api.MasterDataPort masterData;

    public ReworkService(JdbcTemplate jdbcTemplate,
                         CodeGenerator codeGenerator,
                         ProductionService productionService,
                         NoticePort notice,
                         com.mes.masterdata.api.MasterDataPort masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.productionService = productionService;
        this.notice = notice;
        this.masterData = masterData;
    }

    /**
     * 建返修工单。对外契约见 {@link ReworkPort}。
     *
     * <p>{@code fromSeq} 是返修起点工序序号：空表示整件从第一道返修；
     * 不良发生在哪道工序，返修就从哪道工序起做，前面的工序不再重复加工。</p>
     */
    @Override
    @Transactional
    public long createRework(ReworkRequest request) {
        long productId = request.productId();
        BigDecimal qty = request.qty();
        Long parentWorkOrderId = request.parentWorkOrderId();
        Long salesOrderId = request.salesOrderId();
        Long salesOrderItemId = request.salesOrderItemId();
        LocalDate planEnd = request.planEnd();
        String reason = request.reason();
        Integer fromSeq = request.fromSeq();
        if (qty == null || qty.signum() <= 0) {
            throw new BizException(1002, "返修数量必须大于 0");
        }
        String workNo = codeGenerator.next("WO");
        Long id = jdbcTemplate.queryForObject("""
                insert into work_order (work_no, sales_order_id, sales_order_item_id, product_id, plan_qty,
                                        status, work_type, parent_work_order_id, priority,
                                        plan_start, plan_end, rework_from_seq)
                values (?, ?, ?, ?, ?, 'DRAFT', 'REWORK', ?, 'URGENT', current_date,
                        coalesce(cast(? as date), current_date + 7), ?)
                returning id
                """, Long.class, workNo, salesOrderId, salesOrderItemId, productId, qty,
                parentWorkOrderId, planEnd, fromSeq);
        if (id == null) {
            throw new BizException(2018, "返修工单创建失败");
        }
        productionService.createOperations(id, productId, qty, fromSeq);
        productionService.snapshotBom(id, productId, qty, fromSeq);

        String productName = masterData.product(productId)
                .map(com.mes.masterdata.api.ProductInfo::name)
                .orElse(String.valueOf(productId));
        String startText = "";
        if (fromSeq != null) {
            List<Map<String, Object>> startOp = jdbcTemplate.queryForList("""
                    select name from work_order_operation where work_order_id = ? and seq = ?
                    """, id, fromSeq);
            startText = "，从第 " + fromSeq + " 道工序"
                    + (startOp.isEmpty() ? "" : "（" + startOp.get(0).get("name") + "）")
                    + "起返修";
        }
        String content = "产品 " + productName + " × " + qty.stripTrailingZeros().toPlainString()
                + startText
                + (reason == null || reason.isBlank() ? "" : "，原因：" + reason);
        notice.pushToRoles(List.of("PLANNER", "ADMIN"), NoticeMessage.of("TASK",
                "返修工单 " + workNo + " 待排产", content, "WORK_ORDER", workNo));
        if (parentWorkOrderId != null) {
            List<Map<String, Object>> parent = jdbcTemplate.queryForList(
                    "select work_no from work_order where id = ?", parentWorkOrderId);
            if (!parent.isEmpty()) {
                notice.pushToRoles(List.of("QC"), NoticeMessage.of("INFO",
                        "原工单 " + parent.get(0).get("work_no") + " 产生返修",
                        content + "，返修完成后请复检", "WORK_ORDER", workNo));
            }
        }
        return id;
    }
}
