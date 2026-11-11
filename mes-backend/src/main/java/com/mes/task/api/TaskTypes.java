package com.mes.task.api;

/**
 * 任务类型与固定文案。全流程五个交接点各对应一类任务，改名只改这里。
 *
 * <ul>
 *   <li>{@code MRP_RUN} 订单转生产后，计划要跑 MRP 算料并处理缺口；</li>
 *   <li>{@code WO_ASSIGN} 工单下达后，计划要把工序分到人；</li>
 *   <li>{@code WO_PICK} 工单下达后，仓库要按 BOM 发料；</li>
 *   <li>{@code SHIP_READY} 完工检合格后，销售要开单发货；</li>
 *   <li>{@code REQ_CONVERT} 算料生成采购申请后，采购要把已批准的行转成采购单。</li>
 * </ul>
 */
public final class TaskTypes {

    public static final String MRP_RUN = "MRP_RUN";
    public static final String WO_ASSIGN = "WO_ASSIGN";
    public static final String WO_PICK = "WO_PICK";
    public static final String SHIP_READY = "SHIP_READY";
    public static final String REQ_CONVERT = "REQ_CONVERT";
    public static final String QUOTE_REVIEW = "QUOTE_REVIEW";
    public static final String BOM_TASK = "BOM_TASK";

    public static final String REF_SALES_ORDER = "SALES_ORDER";
    public static final String REF_WORK_ORDER = "WORK_ORDER";
    public static final String REF_REQUISITION = "REQUISITION";
    public static final String REF_QUOTE = "QUOTE";

    public static final String PRIORITY_LOW = "LOW";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";

    private TaskTypes() {
    }

    /** 订单紧急程度映射到任务优先级，避免任务页看不出轻重（订单侧只有 NORMAL / URGENT / RUSH） */
    public static String priorityOf(String orderPriority) {
        if (orderPriority == null) {
            return PRIORITY_NORMAL;
        }
        return switch (orderPriority.trim().toUpperCase()) {
            case "URGENT", "RUSH" -> PRIORITY_URGENT;
            case "HIGH" -> PRIORITY_HIGH;
            case "LOW" -> PRIORITY_LOW;
            default -> PRIORITY_NORMAL;
        };
    }

    public static String label(String taskType) {
        if (taskType == null) {
            return "任务";
        }
        return switch (taskType) {
            case MRP_RUN -> "算料";
            case WO_ASSIGN -> "派工";
            case WO_PICK -> "领料";
            case SHIP_READY -> "待发货";
            case REQ_CONVERT -> "转采购单";
            case QUOTE_REVIEW -> "报价评审";
            case BOM_TASK -> "编 BOM";
            default -> taskType;
        };
    }
}
