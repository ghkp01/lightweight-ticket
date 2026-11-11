package com.mes.platform.event;

/**
 * 领域事件订阅者。
 *
 * <p>实现类放在消费方模块里，只依赖发布方 {@code api} 包的事件类，
 * 不依赖发布方的 Service 实现 —— 这条约束由架构守卫测试 {@code ArchitectureBoundaryTest} 保证。</p>
 *
 * <p>一个事件可以有多个订阅者，投递顺序按 Spring 注入顺序（可用 {@code @Order} 调整）。
 * 任何订阅者抛异常都会让整个业务事务回滚，和原来的同步调用行为一致。</p>
 */
public interface DomainEventHandler {

    /** 订阅的事件类型，与 {@link DomainEvent#eventType()} 对应 */
    String eventType();

    void handle(DomainEvent event);
}
