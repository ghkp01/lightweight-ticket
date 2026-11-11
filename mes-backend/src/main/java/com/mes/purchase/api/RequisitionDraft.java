package com.mes.purchase.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 新建 / 修改采购申请的完整内容（对外契约）。
 *
 * <p>MRP 算完缺口后要「一键生成采购申请」，这是 planning 模块唯一需要采购模块的东西；
 * 原来它直接引用 {@code RequisitionService.RequisitionRequest} 这个实现类里的嵌套 record，
 * 意味着拆进程时连 DTO 都得跟着搬。现在两边都只认 api 包里的这一份定义。</p>
 *
 * @param sourceType  来源：PRODUCTION 生产 / STOCK 备库 / MANUAL 手工 / URGENT 紧急
 * @param department  申请部门
 * @param workOrderId 来源工单，可空
 * @param needDate    需求日期
 * @param purpose     用途说明
 * @param remark      备注
 * @param items       明细行
 * @param category    采购类别：MATERIAL 原辅料 / OUTSOURCE 外协 / SPARE 设备备件 / EQUIPMENT 设备 / SERVICE 服务；
 *                    审批链按它分流，留空按原辅料
 * @param urgent      是否紧急，留空时跟随来源（URGENT 来源视为紧急）
 * @param budgetNo    预算科目，可空
 */
public record RequisitionDraft(String sourceType, String department, Long workOrderId,
                               LocalDate needDate, String purpose, String remark,
                               List<RequisitionLine> items, String category, Boolean urgent,
                               String budgetNo) {

    /** 兼容老调用方：只给来源、部门、工单、需求日、用途、备注与明细 */
    public RequisitionDraft(String sourceType, String department, Long workOrderId,
                            LocalDate needDate, String purpose, String remark,
                            List<RequisitionLine> items) {
        this(sourceType, department, workOrderId, needDate, purpose, remark, items, null, null, null);
    }
}
