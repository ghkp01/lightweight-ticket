-- 批次追溯：库存从「产品 + 仓库」细化为「产品 + 仓库 + 批次」，汇总表继续保留用于查询与预警
create table inventory_batch (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id),
    warehouse_id bigint not null references warehouse(id),
    batch_no varchar(64) not null,
    qty numeric(18,4) not null default 0,
    locked_qty numeric(18,4) not null default 0,
    source varchar(16),
    work_order_id bigint references work_order(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint uk_inventory_batch unique (product_id, warehouse_id, batch_no)
);

create index idx_inventory_batch_no on inventory_batch (batch_no);
create index idx_inventory_batch_product on inventory_batch (product_id, warehouse_id);

-- 流水带批次，出库按批次逐条记账，追溯才有据可查
alter table stock_txn add column if not exists batch_no varchar(64);
create index idx_stock_txn_batch on stock_txn (batch_no);

-- 工单实际消耗了哪些原料批次（领料时写入）
create table work_order_material_batch (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id),
    product_id bigint not null references product(id),
    batch_no varchar(64) not null,
    qty numeric(18,4) not null,
    issue_ref varchar(32),
    created_at timestamptz not null default now()
);

create index idx_womb_batch on work_order_material_batch (batch_no);
create index idx_womb_work_order on work_order_material_batch (work_order_id);

-- 发货实际发走了哪些成品批次
create table shipment_batch (
    id bigint generated always as identity primary key,
    shipment_item_id bigint not null references shipment_item(id) on delete cascade,
    batch_no varchar(64) not null,
    qty numeric(18,4) not null,
    created_at timestamptz not null default now()
);

create index idx_shipment_batch_no on shipment_batch (batch_no);
create index idx_shipment_batch_item on shipment_batch (shipment_item_id);

-- 历史库存归到一个期初批次，保证「批次合计 = 汇总库存」，升级后不影响既有库存口径
insert into inventory_batch (product_id, warehouse_id, batch_no, qty, source)
select product_id, warehouse_id, 'INIT', qty, 'ADJUST'
from inventory
where qty <> 0
on conflict (product_id, warehouse_id, batch_no) do nothing;

update stock_txn set batch_no = 'INIT' where batch_no is null;

-- 追溯菜单权限：管理员、计划、仓库、质检可见
insert into sys_role_permission (role_id, permission) values
(1, 'trace'), (3, 'trace'), (4, 'trace'), (5, 'trace')
on conflict do nothing;
