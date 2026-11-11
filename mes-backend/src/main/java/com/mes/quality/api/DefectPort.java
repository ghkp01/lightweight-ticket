package com.mes.quality.api;

/**
 * 不良台账的写入口契约。
 *
 * <p>报工不良（生产）、完工检与来料检不合格（质检自己）、退货报废（退货）都要落一条不良记录。
 * 这三处分别在三个模块，原来都直接注入 {@code DefectWriter} 这个实现类；
 * 现在只依赖接口，质量模块内部怎么组织（是否拆分、是否异步）不再外泄。</p>
 *
 * <p>返回新建不良记录的 id；数量为空或非正数时返回 0（不落库、不报错）。</p>
 */
public interface DefectPort {

    long write(DefectRequest request);
}
