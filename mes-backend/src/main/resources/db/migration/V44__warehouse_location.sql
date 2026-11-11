-- 库位管理：库位主数据 + 批次上架库位 + 移库记录
create table if not exists warehouse_location (
    id bigint generated always as identity primary key,
    warehouse_id bigint not null references warehouse(id) on delete cascade,
    code varchar(32) not null,
    name varchar(64) not null,
    zone varchar(32),
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    unique (warehouse_id, code)
);

create index if not exists idx_location_warehouse on warehouse_location (warehouse_id, enabled);

comment on table warehouse_location is '库位：挂在仓库下，编码在仓库内唯一';
comment on column warehouse_location.zone is '库区，例如 A 区、冷藏区，便于分区拣货';

alter table inventory_batch add column if not exists location_id bigint references warehouse_location(id);
comment on column inventory_batch.location_id is '该批次当前所在库位，空表示未上架';

create index if not exists idx_batch_location on inventory_batch (location_id);

alter table inbound_order_item add column if not exists location_code varchar(32);
comment on column inbound_order_item.location_code is '入库时指定的库位编码，空表示不上架';

create table if not exists stock_move (
    id bigint generated always as identity primary key,
    move_no varchar(32) not null,
    product_id bigint not null references product(id),
    warehouse_id bigint not null references warehouse(id),
    batch_no varchar(64) not null,
    qty numeric(18,4) not null,
    from_location_id bigint references warehouse_location(id),
    to_location_id bigint not null references warehouse_location(id),
    operator varchar(64),
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_move_qty check (qty > 0)
);

create index if not exists idx_move_batch on stock_move (product_id, warehouse_id, batch_no);

comment on table stock_move is '移库记录：批次在仓库内换库位的流水，不改变总库存';
