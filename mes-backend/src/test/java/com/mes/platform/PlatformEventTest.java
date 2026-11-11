package com.mes.platform;

import com.mes.inventory.InventoryService;
import com.mes.inventory.api.InventoryPort;
import com.mes.platform.event.DomainEvent;
import com.mes.platform.event.DomainEventHandler;
import com.mes.platform.event.DomainEventPublisher;
import com.mes.warehouse.api.InboundCompleted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台层：领域事件（Outbox + 同进程投递）与模块契约（Port）的落地验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlatformEventTest {

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryPort inventoryPort;

    @Autowired
    private ProbeHandler probe;

    @AfterEach
    void tearDown() {
        probe.reset();
    }

    @Test
    @DisplayName("发布事件会落 Outbox 并同步投递给订阅者")
    void publishWritesOutboxAndDispatches() {
        ProbeEvent event = new ProbeEvent(7L, "PROBE-7");
        long eventId = publisher.publish(event);

        assertTrue(eventId > 0, "应返回 Outbox 行 id");
        assertEquals(1, probe.calls.get(), "同进程订阅者应被调用一次");

        Map<String, Object> row = jdbc.queryForMap("""
                select event_type, aggregate_id, status, handled_at
                from sys_domain_event where id = ?
                """, eventId);
        assertEquals("PROBE_EVENT", row.get("event_type"));
        assertEquals("PROBE-7", row.get("aggregate_id"));
        assertEquals("SENT", row.get("status"));
        assertTrue(row.get("handled_at") != null, "投递完成后应记录时间");
    }

    @Test
    @DisplayName("采购订阅了入库完成事件：仓储不再直接依赖采购")
    void receiptListenerSubscribed() {
        assertEquals(1, publisher.subscriberCount(InboundCompleted.TYPE),
                "入库完成事件应刚好有一个订阅者（采购回写实收）");
    }

    @Test
    @DisplayName("库存对外只暴露 InventoryPort，调用方拿不到实现类")
    void inventoryExposedViaPort() {
        assertInstanceOf(InventoryService.class, inventoryPort, "Port 应绑定到本地实现");
    }

    // ------------------------------------------------------------------ 测试替身

    record ProbeEvent(long id, String code) implements DomainEvent {
        @Override
        public String eventType() {
            return "PROBE_EVENT";
        }

        @Override
        public String aggregateType() {
            return "PROBE";
        }

        @Override
        public String aggregateId() {
            return code;
        }
    }

    static class ProbeHandler implements DomainEventHandler {

        final AtomicInteger calls = new AtomicInteger();
        final List<String> received = new ArrayList<>();

        void reset() {
            calls.set(0);
            received.clear();
        }

        @Override
        public String eventType() {
            return "PROBE_EVENT";
        }

        @Override
        public void handle(DomainEvent event) {
            calls.incrementAndGet();
            received.add(event.aggregateId());
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeHandler probeHandler() {
            return new ProbeHandler();
        }
    }
}
