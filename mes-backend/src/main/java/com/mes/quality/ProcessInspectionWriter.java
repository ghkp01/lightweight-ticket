package com.mes.quality;

import com.mes.common.CodeGenerator;
import com.mes.quality.api.ProcessInspectionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工序随工检验的薄实现：只碰 {@code inspection}，单号走平台的 {@link CodeGenerator}。
 *
 * <p>刻意不复用 {@code InspectionService}：那个类依赖生产契约（工单、工序快照），
 * 生产报工回头再注入它就成构造环。</p>
 */
@Component
public class ProcessInspectionWriter implements ProcessInspectionPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;

    public ProcessInspectionWriter(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
    }

    @Override
    public String record(ProcessInspection inspection) {
        String inspectionNo = codeGenerator.next("QC");
        jdbcTemplate.update("""
                insert into inspection (inspection_no, type, ref_type, ref_no, work_order_id, product_id,
                                        inspect_qty, defect_qty, result, inspector, inspected_at, remark)
                values (?, 'PROCESS', 'WORK_ORDER', ?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, inspectionNo, inspection.refNo(), inspection.workOrderId(), inspection.productId(),
                inspection.inspectQty(), inspection.defectQty(), inspection.result(),
                inspection.inspector(), inspection.remark());
        return inspectionNo;
    }
}
