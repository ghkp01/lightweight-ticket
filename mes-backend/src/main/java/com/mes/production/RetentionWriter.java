package com.mes.production;

import com.mes.production.api.RetentionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 生产侧历史数据清理：只处理生产模块自己的单件流转日志。
 */
@Service
public class RetentionWriter implements RetentionPort {

    private final JdbcTemplate jdbcTemplate;

    public RetentionWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int purgeUnitLogsBefore(LocalDate date) {
        return jdbcTemplate.update("delete from work_order_unit_log where created_at::date < ?", date);
    }
}
