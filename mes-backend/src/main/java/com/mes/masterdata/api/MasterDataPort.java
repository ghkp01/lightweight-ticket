package com.mes.masterdata.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基础数据对外契约（产品 / 仓库 / 客户）。
 *
 * <p>这是拆分前的第三个样板：基础数据被引用得最广（`product` 被别的模块查了 35 处、
 * `warehouse` 17 处、`customer` 7 处），但**没有任何模块 import 过 masterdata 的类** ——
 * 耦合全在 SQL 里，压根没有接口层。补上这层之后，别的模块不必知道
 * 「产品表叫什么、成本字段是 cost 还是 avg_cost」。</p>
 *
 * <p><b>为什么按 id 批量取</b>：列表页最常见的是「本表查 20 行，再补产品名称」。
 * 如果只提供单个查询，改造完必然 N+1。所以 {@link #products(Collection)} 从一开始就按批量设计，
 * 后续把 `join product` 改掉时能一次查一页的 id。</p>
 *
 * <p><b>已经覆盖到的</b>：按 id 批量取（产品 / 仓库 / 客户）、按编码取、按关键字换 id 集合
 * （{@link #searchProductIds} / {@link #searchCustomerIds}）、工厂可见仓库集合、回写移动平均成本。
 * 列表页的「join 主数据补名称」「按主数据搜索」「按工厂隔离」三类都可以用这些方法替掉，
 * 不再需要在业务 SQL 里出现 {@code product} / {@code warehouse} / {@code customer} 这几张表。</p>
 */
public interface MasterDataPort {

    /** 按 id 取产品，不存在返回空 */
    Optional<ProductInfo> product(long id);

    /** 按编码取产品，导入/扫码场景用 */
    Optional<ProductInfo> productByCode(String code);

    /** 批量取产品，返回 id → 产品；查不到的不在 map 里 */
    Map<Long, ProductInfo> products(Collection<Long> ids);

    /**
     * 按编码或名称模糊搜索产品 id。
     *
     * <p>补这个方法是为了替掉 {@code join product p ... and (p.code ilike ? or p.name ilike ?)}
     * 这类「跨模块条件查询」：别的模块拿不到产品表，只能先问 masterdata 要 id 集合，
     * 再在本地 SQL 里用 {@code product_id in (...)} 过滤。</p>
     *
     * <p>返回结果按 id 上限截断（500 条），模糊搜索本来就用于人工筛选，
     * 真出现「一次匹配上万产品」的场景应该去做专门的检索页，而不是靠 like。</p>
     */
    Set<Long> searchProductIds(String keyword);

    /** 按 id 取仓库 */
    Optional<WarehouseInfo> warehouse(long id);

    /** 批量取仓库 */
    Map<Long, WarehouseInfo> warehouses(Collection<Long> ids);

    /** 按仓库类型取 id（RAW / FIN / SEMI…），找不到抛业务异常 */
    long warehouseIdOfType(String type);

    /**
     * 某工厂数据范围内可见的仓库 id：本厂仓库 + 未归属工厂的仓库。
     *
     * <p>为什么需要这个方法：别的模块的列表页原来靠 {@code join warehouse w ... and
     * (w.factory_org_id = ? or w.factory_org_id is null)} 做工厂隔离，join 一去掉这条条件就没处落。
     * 改成「先问 masterdata 要一份 id 集合，再用 {@code warehouse_id in (...)} 过滤」，
     * 语义等价且不再碰别人的表。仓库数量是几十个量级，拼进 IN 没有压力。</p>
     */
    Set<Long> visibleWarehouseIds(Long factoryOrgId);

    /** 按 id 取客户 */
    Optional<CustomerInfo> customer(long id);

    /** 批量取客户，返回 id → 客户；查不到的不在 map 里 */
    Map<Long, CustomerInfo> customers(Collection<Long> ids);

    /** 按编码或名称模糊搜索客户 id，用于替掉列表里的 {@code join customer ... c.name ilike ?} */
    Set<Long> searchCustomerIds(String keyword);

    /**
     * 回写产品移动平均成本。
     *
     * <p>{@code avg_cost} 在产品档案上，但它的算法属于库存（每次入库按「原结存 + 本次金额」重算）。
     * 库存算完把结果写回来，比库存直接 {@code update product} 更清楚：字段归属不变，
     * 算法留在库存侧。</p>
     */
    void updateAvgCost(long productId, java.math.BigDecimal avgCost);

    /** 产品是否存在（含已停用），建单校验用 */
    default boolean productExists(long id) {
        return product(id).isPresent();
    }

    /**
     * 当前生效 BOM 的用料行；物料没有生效 BOM 时返回空列表。
     *
     * <p>用量已按 BOM 头基础数量折算成「每个父件用量」，生产工单快照与 MRP
     * 不用再自己判断 BOM 版本、状态与生效日期。</p>
     */
    List<BomLineInfo> activeBomLines(long productId);

    /** 当前生效 BOM 的 id；没有生效 BOM 返回 null（BOM 任务与 MRP 门槛用） */
    Long activeBomId(long productId);

    /** 仓库是否存在且启用，建单校验用 */
    default boolean warehouseEnabled(long id) {
        return warehouse(id).filter(WarehouseInfo::enabled).isPresent();
    }
}
