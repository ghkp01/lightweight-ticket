package com.mes.platform.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 领域事件发布器：把事件写进 Outbox，再同步投递给本进程内的订阅者。
 *
 * <p><b>为什么先落库再投递</b>：落库让「谁在什么时候发了什么事件」有据可查，
 * 也是将来接 MQ 的现成出口（中继任务扫 PENDING 发出去即可）。
 * 现在同步投递是为了保持原有语义——订阅者抛异常时业务事务一起回滚，
 * 和以前「仓储入库完直接调采购回写实收」完全一致，不会出现「单据成功了但实收没回写」。</p>
 *
 * <p><b>接 MQ 时怎么改</b>：把 {@link #dispatch} 换成「发消息并把状态置 PENDING」，
 * 由中继任务消费确认后改成 SENT；业务侧的 publish 调用一行都不用改。</p>
 *
 * <p>订阅者用 {@link ObjectProvider} 延迟取：发布器本身被仓储/采购的 Service 依赖，
 * 如果构造时就把所有订阅者拉起来，会出现「发布器 → 订阅者 → 采购 Service → … → 发布器」的循环依赖。
 * 改成投递时才取（并按 {@code @Order} 排序），既解开循环，也不改变投递顺序语义。</p>
 */
@Service
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DomainEventHandler> handlerProvider;

    public DomainEventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                ObjectProvider<DomainEventHandler> handlerProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.handlerProvider = handlerProvider;
    }

    /**
     * 发布事件：先落 Outbox，再同步投递。
     *
     * @return Outbox 行 id，便于排查
     */
    @Transactional
    public long publish(DomainEvent event) {
        String json = toJson(event);
        Long id = jdbcTemplate.queryForObject("""
                insert into sys_domain_event (event_type, aggregate_type, aggregate_id, payload, status)
                values (?, ?, ?, ?::jsonb, 'PENDING') returning id
                """, Long.class, event.eventType(), event.aggregateType(), event.aggregateId(), json);
        long eventId = id == null ? 0 : id;
        dispatch(eventId, event);
        return eventId;
    }

    /**
     * 同进程同步投递。订阅者抛出的异常不吞：吞掉会让「单据成功但下游没动」，
     * 业务上比直接失败更难查。异常交给调用方事务回滚，Outbox 行也一起回滚。
     */
    private void dispatch(long eventId, DomainEvent event) {
        List<String> handled = new ArrayList<>();
        for (DomainEventHandler handler : handlers()) {
            if (!event.eventType().equals(handler.eventType())) {
                continue;
            }
            handler.handle(event);
            handled.add(handler.getClass().getSimpleName());
        }
        try {
            jdbcTemplate.update("""
                    update sys_domain_event set status = 'SENT', handled_at = now(), error = ?
                    where id = ?
                    """, handled.isEmpty() ? "无订阅者" : null, eventId);
        } catch (RuntimeException error) {
            log.warn("更新事件状态失败：{}", error.getMessage());
        }
        if (!handled.isEmpty()) {
            log.debug("事件 {} 已投递给 {}", event.eventType(), handled);
        }
    }

    /** 供测试与排查用：某个事件类型当前有几个订阅者 */
    public long subscriberCount(String eventType) {
        return handlers().stream().filter(handler -> eventType.equals(handler.eventType())).count();
    }

    /** 投递时才去容器里取订阅者，顺序沿用 {@code @Order} */
    private List<DomainEventHandler> handlers() {
        return handlerProvider.orderedStream().toList();
    }

    private String toJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (Exception error) {
            log.warn("事件序列化失败：{}", error.getMessage());
            return "{}";
        }
    }
}
