-- 成本核算基础：库存流水带单价与金额，产品维护移动平均成本
alter table stock_txn add column if not exists unit_cost numeric(18,6) not null default 0;
alter table stock_txn add column if not exists amount numeric(18,2) not null default 0;
alter table product add column if not exists avg_cost numeric(18,6) not null default 0;

-- 已有产品先用档案成本兜底，后续入库按移动加权刷新
update product set avg_cost = cost where avg_cost = 0 and cost > 0;

comment on column stock_txn.unit_cost is '本次流水的单位成本：入库取实际/档案成本，出库取移动平均成本';
comment on column stock_txn.amount is '数量 × 单位成本，出库为负';
comment on column product.avg_cost is '移动加权平均成本，随入库刷新，出库按它计价';

-- 入库明细单价：采购收货自动带采购单价，手工入库可填 0（退回档案成本）
alter table inbound_order_item add column if not exists unit_price numeric(18,6) not null default 0;
comment on column inbound_order_item.unit_price is '入库单价，0 表示未指定，按产品档案成本入库';

-- 工单成本快照：料 / 工 / 费，完工时结算，也可手动重算
alter table work_order add column if not exists material_cost numeric(18,2) not null default 0;
alter table work_order add column if not exists labor_cost numeric(18,2) not null default 0;
alter table work_order add column if not exists overhead_cost numeric(18,2) not null default 0;
alter table work_order add column if not exists total_cost numeric(18,2) not null default 0;
alter table work_order add column if not exists unit_cost numeric(18,6) not null default 0;
alter table work_order add column if not exists costed_at timestamptz;

comment on column work_order.material_cost is '材料成本：领料流水金额 - 退料流水金额';
comment on column work_order.labor_cost is '人工成本：报工数量 × 工序单价';
comment on column work_order.overhead_cost is '制造费用：人工成本 × 配置比例（manufacturing.overhead.rate）';
comment on column work_order.unit_cost is '单位成本：总成本 ÷ 合格数量';

insert into sys_config (config_key, config_value, value_type, title, group_name, remark)
values ('manufacturing.overhead.rate', '10', 'NUMBER', '制造费用比例(%)', '成本',
        '按人工成本的比例摊制造费用；填 0 表示不摊制造费用')
on conflict (config_key) do nothing;
