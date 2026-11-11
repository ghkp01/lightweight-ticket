package com.mes.masterdata.api;

import com.mes.platform.scope.OrgScopeService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 契约配套的客户端工具：把「列表页 join 主数据补名称」和「按工厂做数据范围」这两件
 * 每个模块都要干的事，收成三个静态方法。
 *
 * <p>为什么放在 api 包里而不是各模块自己写：这两种逻辑原本分散在几十个 SQL 里，
 * 一段段复制会出现「一个模块把未归属仓库藏了、另一个没藏」这种口径漂移。
 * 放在契约包里，它跟着 masterdata 的 api 一起发布 —— 将来 masterdata 拆成独立服务，
 * 这份工具变成「远程调用 + 本地拼装」的地方，调用方仍然一行不改。</p>
 *
 * <p>约定：{@code rows} 里的 id 列必须是 {@code Number}（PostgreSQL 的 bigint 走 JDBC 都是 Long）；
 * 查不到对应主数据时把名称列填 {@code null}，不抛异常 —— 主数据被删了不该让列表 500。</p>
 */
public final class MasterDataViews {

    private MasterDataViews() {
    }

    /**
     * 批量补仓库名。
     *
     * @param idThenName 成对的「id 列名, 名称列名」，例如 {@code "fromWarehouseId", "fromWarehouseName"}
     */
    public static void fillWarehouseNames(MasterDataPort port, List<Map<String, Object>> rows,
                                         String... idThenName) {
        Set<Long> ids = collectIds(rows, idThenName);
        Map<Long, WarehouseInfo> warehouses = port.warehouses(ids);
        for (Map<String, Object> row : rows) {
            for (int i = 0; i + 1 < idThenName.length; i += 2) {
                WarehouseInfo info = lookup(warehouses, row.get(idThenName[i]));
                row.put(idThenName[i + 1], info == null ? null : info.name());
            }
        }
    }

    /** 批量补产品编码 / 名称 / 规格 / 单位，id 列固定叫 {@code productId} */
    public static void fillProductNames(MasterDataPort port, List<Map<String, Object>> rows) {
        Set<Long> ids = collectIds(rows, "productId");
        Map<Long, ProductInfo> products = port.products(ids);
        for (Map<String, Object> row : rows) {
            ProductInfo info = lookup(products, row.get("productId"));
            row.put("productCode", info == null ? null : info.code());
            row.put("productName", info == null ? null : info.name());
            row.put("spec", info == null ? null : info.spec());
            row.put("unit", info == null ? null : info.unit());
        }
    }

    /**
     * 批量补客户名。
     *
     * @param idThenName 成对的「id 列名, 名称列名」，默认 {@code "customerId", "customerName"}
     */
    public static void fillCustomerNames(MasterDataPort port, List<Map<String, Object>> rows,
                                        String... idThenName) {
        String[] columns = idThenName.length == 0
                ? new String[]{"customerId", "customerName"} : idThenName;
        Set<Long> ids = collectIds(rows, columns);
        Map<Long, CustomerInfo> customers = port.customers(ids);
        for (Map<String, Object> row : rows) {
            for (int i = 0; i + 1 < columns.length; i += 2) {
                CustomerInfo info = lookup(customers, row.get(columns[i]));
                row.put(columns[i + 1], info == null ? null : info.name());
            }
        }
    }

    /**
     * 工厂数据范围：把「仓库属于本厂」翻译成 {@code 列 in (...)}。
     *
     * <p>各模块原来靠 {@code join warehouse w ... and (w.factory_org_id = ? or w.factory_org_id is null)}
     * 做隔离，join 去掉后条件没处落。这里改成向 masterdata 要一份「本厂 + 未归属」的仓库 id 集合：
     * 语义完全等价（未归属的历史仓库仍然可见），也不再碰别人的表。</p>
     *
     * @param column 单据上的仓库列，例如 {@code "o.warehouse_id"}
     */
    public static void appendWarehouseScope(OrgScopeService scope, MasterDataPort port,
                                            StringBuilder where, List<Object> args, String column) {
        Long factory = scope.listFilter();
        if (factory == null) {
            return;
        }
        Set<Long> ids = port.visibleWarehouseIds(factory);
        if (ids.isEmpty()) {
            // 该厂一个仓库都没有：等价于「按 join 过滤后一行不剩」
            where.append(" and 1 = 0 ");
            return;
        }
        where.append(" and ").append(column).append(" in (")
                .append(String.join(",", ids.stream().map(id -> "?").toList())).append(") ");
        args.addAll(ids);
    }

    private static Set<Long> collectIds(List<Map<String, Object>> rows, String... columns) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (String column : columns) {
                if (row.get(column) instanceof Number number) {
                    ids.add(number.longValue());
                }
            }
        }
        return ids;
    }

    private static <T> T lookup(Map<Long, T> candidates, Object id) {
        return id instanceof Number number ? candidates.get(number.longValue()) : null;
    }
}
