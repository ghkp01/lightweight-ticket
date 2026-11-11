package com.mes.production;

import com.mes.production.api.WorkOrderCostPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 工单成本字段写入的薄实现：只碰 {@code work_order}，不带任何依赖。
 *
 * <p>刻意不复用 {@link ProductionService}：那边依赖成本契约（完工时结算），
 * 直接互相注入会成构造环。</p>
 */
@Component
public class WorkOrderCostWriter implements WorkOrderCostPort {

    private final JdbcTemplate jdbcTemplate;

    public WorkOrderCostWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void writeCost(long workOrderId, BigDecimal materialCost, BigDecimal laborCost,
                          BigDecimal overheadCost, BigDecimal outsourceCost,
                          BigDecimal totalCost, BigDecimal unitCost) {
        jdbcTemplate.update("""
                update work_order
                set material_cost = ?, labor_cost = ?, overhead_cost = ?, outsource_cost = ?,
                    total_cost = ?, unit_cost = ?, costed_at = now(), updated_at = now(),
                    version = version + 1
                where id = ?
                """, materialCost, laborCost, overheadCost, outsourceCost, totalCost, unitCost,
                workOrderId);
    }
}
