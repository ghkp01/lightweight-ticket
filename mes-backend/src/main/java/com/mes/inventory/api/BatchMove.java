package com.mes.inventory.api;

import java.math.BigDecimal;

/**
 * 一次库存变动实际动到的批次与数量。
 *
 * <p>放在 {@code api} 包而不是实现类里：生产领料、销售发货都要记「这批货是从哪个批次扣的」，
 * 把返回类型收进契约层，消费方就不用依赖 {@code InventoryService} 这个实现类。</p>
 */
public record BatchMove(String batchNo, BigDecimal qty) {
}
