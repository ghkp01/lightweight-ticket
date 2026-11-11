package com.mes.masterdata.api;

/**
 * 客户对外只读视图：销售单需要编码与名称做校验与展示，
 * 详情页还要联系人、电话、地址（打印销售单时直接用）。
 */
public record CustomerInfo(long id, String code, String name, boolean enabled,
                           String contact, String phone, String address) {

    /** 只要身份与名称的场景（列表、校验）用这个，避免每次写全参 */
    public CustomerInfo(long id, String code, String name, boolean enabled) {
        this(id, code, name, enabled, null, null, null);
    }
}
