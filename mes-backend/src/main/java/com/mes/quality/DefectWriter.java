package com.mes.quality;

import com.mes.common.CodeGenerator;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.quality.api.DefectPort;
import com.mes.quality.api.DefectRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不良记录的写入口：报工不良、完工检不合格、退货报废都落在这里。
 * 单独抽出来是为了避免 ProductionService 与 DefectService 相互依赖。
 *
 * <p>对外只暴露 {@link DefectPort}，调用方不再感知这里的实现细节。</p>
 */
@Component
public class DefectWriter implements DefectPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final NoticePort notice;

    public DefectWriter(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator, NoticePort notice) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.notice = notice;
    }

    @Override
    public long write(DefectRequest request) {
        if (request == null || request.qty() == null || request.qty().signum() <= 0) {
            return 0;
        }
        String defectNo = codeGenerator.next("DF");
        Long id = jdbcTemplate.queryForObject("""
                insert into defect_record (defect_no, work_order_id, operation_id, process_id, product_id,
                                           qty, source, reason)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, defectNo, request.workOrderId(), request.operationId(),
                request.processId(), request.productId(), request.qty(), request.source(),
                request.reason());
        if (request.pushNotice()) {
            String content = "不良数量 " + request.qty().stripTrailingZeros().toPlainString()
                    + (request.reason() == null || request.reason().isBlank()
                    ? "" : "，原因：" + request.reason());
            notice.pushToRoles(List.of("QC", "PLANNER"), NoticeMessage.of("ALERT",
                    "新增待处置不良 " + defectNo, content, "DEFECT", defectNo));
        }
        return id == null ? 0 : id;
    }
}
