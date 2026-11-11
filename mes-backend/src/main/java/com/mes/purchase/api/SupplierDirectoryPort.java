package com.mes.purchase.api;

import java.util.List;

/**
 * 供应商档案的对外契约（给基础数据的「导入导出」用）。
 *
 * <p>供应商表归采购所有：评级、账期、交期天数这些字段的口径都在采购侧，
 * 基础数据只是提供了一个批量导入/导出的入口，不应该直接写别人的表。</p>
 */
public interface SupplierDirectoryPort {

    /** 供应商档案行，字段与导入模板一一对应 */
    record SupplierRow(Long id, String code, String name, String category, String contact, String phone,
                       String email, String address, String taxNo, String paymentTerm,
                       Integer creditDays, Integer leadDays, String rating) {

        /** 导入时只有编码及之后的业务字段，没有 id */
        public SupplierRow(String code, String name, String category, String contact, String phone,
                           String email, String address, String taxNo, String paymentTerm,
                           Integer creditDays, Integer leadDays, String rating) {
            this(null, code, name, category, contact, phone, email, address, taxNo, paymentTerm,
                    creditDays, leadDays, rating);
        }
    }

    /** 全量导出，按编码排序 */
    List<SupplierRow> all();

    /** 按编码取 id，找不到返回 null */
    Long idByCode(String code);

    /** 按编码 upsert（编码是唯一键），已存在则覆盖业务字段 */
    void upsert(SupplierRow row);
}
