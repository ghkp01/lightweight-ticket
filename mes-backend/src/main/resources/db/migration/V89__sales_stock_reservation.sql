-- 销售订单成品库存预留（ATP）：按交期先到先得占用现有库存，
-- 避免多张已确认订单在多次 MRP 之间重复占用同一批库存。
create table if not exists sales_stock_reservation (
    id bigserial primary key,
    sales_order_id bigint not null,
    sales_order_item_id bigint not null,
    product_id bigint not null,
    qty numeric(18,4) not null,
    status varchar(16) not null default 'ACTIVE',
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ck_sales_stock_reservation_status
        check (status in ('ACTIVE', 'RELEASED', 'CONSUMED')),
    constraint ck_sales_stock_reservation_qty check (qty >= 0)
);

-- 一条订单明细只允许一条有效预留：重跑 MRP 时先释放再重新分配
create unique index if not exists ux_sales_stock_reservation_active_item
    on sales_stock_reservation (sales_order_item_id) where status = 'ACTIVE';

create index if not exists ix_sales_stock_reservation_product
    on sales_stock_reservation (product_id, status);

create index if not exists ix_sales_stock_reservation_order
    on sales_stock_reservation (sales_order_id, status);

comment on table sales_stock_reservation is
    '销售订单成品库存预留：ATP = 可用库存 - 其它订单的 ACTIVE 预留';
comment on column sales_stock_reservation.status is
    'ACTIVE 占用中 / RELEASED 已释放 / CONSUMED 已发货消耗';
