package com.mes.platform.event;

import java.util.Map;

/**
 * 领域事件：模块对外声明「发生了什么」，其它模块订阅。
 *
 * <p>这是拆分的第一个抓手。原来模块之间直接注入对方 Service，编译期就绑死了；
 * 改成事件后，发布方只依赖自己模块的 {@code api} 包里的这个事件类，
 * 消费方反过来依赖发布方的 {@code api}，编译期依赖从「双向」变成「单向」，
 * 将来把发布方拆成独立服务时，消费方只需要从「本地方法调用」换成「订阅消息」。</p>
 *
 * <p>实现约定：事件类放在发布模块的 {@code api} 包里，用 record 实现，
 * 除接口方法外再暴露强类型访问器，消费方不用碰 payload map。</p>
 */
public interface DomainEvent {

    /** 事件类型，订阅方按它匹配，例如 {@code INBOUND_COMPLETED} */
    String eventType();

    /** 聚合根类型，例如 {@code INBOUND_ORDER} */
    String aggregateType();

    /** 聚合根 id 或单号，排查与重放用 */
    String aggregateId();

    /** 落库用的结构化数据，消费方应优先用强类型访问器 */
    default Map<String, Object> payload() {
        return Map.of();
    }
}
