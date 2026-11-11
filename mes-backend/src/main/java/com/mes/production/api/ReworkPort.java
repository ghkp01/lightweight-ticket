package com.mes.production.api;

/**
 * 返修工单的对外契约。
 *
 * <p>质检（完工检不合格、不良处置选返修）与退货（退货处置选返修）都要开返修单，
 * 但它们不该知道返修单怎么建工序、怎么快照 BOM。返回新建返修工单的 id。</p>
 */
public interface ReworkPort {

    long createRework(ReworkRequest request);
}
