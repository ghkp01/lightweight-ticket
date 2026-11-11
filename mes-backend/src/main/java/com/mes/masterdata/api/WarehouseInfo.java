package com.mes.masterdata.api;

/**
 * 仓库对外只读视图。
 *
 * <p>{@code factoryOrgId} 是工厂归属，调拨、隔离范围、审批都要用；
 * {@code type} 是 RAW / FIN / SEMI 这类仓库类型，库存按类型找仓时用。</p>
 */
public record WarehouseInfo(long id, String code, String name, String type,
                            boolean enabled, Long factoryOrgId) {
}
