package com.mes.masterdata;

import com.mes.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工艺路线初始化：从已有产品复制路线，并留变更记录。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoutingInitTest {

    @Autowired
    private MasterDataService masterDataService;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;
    private long targetId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        sourceId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "RA" + suffix, "路线源产品" + suffix);
        targetId = jdbc.queryForObject("""
                insert into product (code, name, category, unit, price)
                values (?, ?, 'FINISHED', '件', 0) returning id
                """, Long.class, "RB" + suffix, "路线目标产品" + suffix);
        Long cut = jdbc.queryForObject("select id from process where code = 'CUT'", Long.class);
        Long mach = jdbc.queryForObject("select id from process where code = 'MACH'", Long.class);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 1, ?, 1.50, 1.20)
                """, sourceId, cut);
        jdbc.update("""
                insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price)
                values (?, 2, ?, 3.00, 2.50)
                """, sourceId, mach);
    }

    @Test
    @DisplayName("复制工艺路线并记录变更")
    void copyRoutingAndLogChange() {
        Map<String, Object> result = masterDataService.copyRouting(targetId, sourceId, false);
        assertEquals(2, ((Number) result.get("copied")).intValue());
        assertEquals("下料→机加工", result.get("summary"));

        List<Map<String, Object>> routing = masterDataService.routing(targetId);
        assertEquals(2, routing.size());
        assertEquals("下料", routing.get(0).get("processName"));

        List<Map<String, Object>> changes = masterDataService.routingChanges(targetId);
        assertEquals(1, changes.size());
        assertEquals("COPY", changes.get(0).get("action"));
        assertEquals("下料→机加工", changes.get(0).get("newSummary"));
    }

    @Test
    @DisplayName("已有路线需显式覆盖，且不能从自身复制")
    void guardDuplicateAndSelfCopy() {
        masterDataService.copyRouting(targetId, sourceId, false);
        BizException duplicate = assertThrows(BizException.class,
                () -> masterDataService.copyRouting(targetId, sourceId, false));
        assertEquals(2004, duplicate.getCode());

        BizException self = assertThrows(BizException.class,
                () -> masterDataService.copyRouting(targetId, targetId, true));
        assertEquals(1002, self.getCode());

        Map<String, Object> overwritten = masterDataService.copyRouting(targetId, sourceId, true);
        assertEquals(2, ((Number) overwritten.get("copied")).intValue());
        assertEquals(2, masterDataService.routingChanges(targetId).size());
    }
}
