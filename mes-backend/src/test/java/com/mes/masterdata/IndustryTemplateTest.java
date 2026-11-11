package com.mes.masterdata;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IndustryTemplateTest {

    @Autowired
    private DataExchangeService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("行业模板一次写入产品 / BOM / 工艺路线 / 客户")
    void applyElectronicsTemplate() {
        Map<String, Object> result = service.applyTemplate("electronics");
        assertEquals(0, ((Number) result.get("failed")).intValue());
        assertEquals(4, count("select count(*) from product where code like '%-ELEC-%'"));
        assertEquals(4, count("""
                select count(*) from bom_item b
                join bom h on h.id = b.bom_id
                join product p on p.id = h.product_id
                where p.code like '%-ELEC-%'
                """));
        assertEquals(6, count("""
                select count(*) from product_routing r join product p on p.id = r.product_id
                where p.code like '%-ELEC-%'
                """));
        assertEquals(1, count("select count(*) from customer where code = 'C-ELEC-01'"));
    }

    @Test
    @DisplayName("未知行业模板被拒绝")
    void unknownTemplateRejected() {
        assertThrows(BizException.class, () -> service.applyTemplate("unknown"));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
