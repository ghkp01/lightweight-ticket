-- 入库来源收紧与异常：入库单标记来源、无单入库留痕、采购退货

-- 1. 入库来源：PO 采购单收货 / MANUAL 无单入库（例外）/ RETURN 退货入库
alter table inbound_order add column if not exists source varchar(16) not null default 'MANUAL';
alter table inbound_order add column if not exists no_order_reason varchar(255);
comment on column inbound_order.source is '来源：PO 采购单收货 / MANUAL 无单入库例外 / RETURN 退货入库';
comment on column inbound_order.no_order_reason is '无来源单据入库的原因说明，采购收货为空';

update inbound_order set source = 'PO' where purchase_order_id is not null;
update inbound_order set source = 'RETURN'
where purchase_order_id is null and type = 'RETURN';
update inbound_order set no_order_reason = '历史入库单补录，来源为手工建单'
where source = 'MANUAL' and no_order_reason is null;

alter table inbound_order drop constraint if exists ck_inbound_source;
alter table inbound_order add constraint ck_inbound_source
    check (source in ('PO', 'MANUAL', 'RETURN'));

-- 采购来源必须挂采购单，避免出现「采购入库但没有采购单」的脏数据
alter table inbound_order drop constraint if exists ck_inbound_purchase_source;
alter table inbound_order add constraint ck_inbound_purchase_source
    check (source <> 'PO' or purchase_order_id is not null);

create index if not exists idx_inbound_source on inbound_order (source, status);

-- 2. 采购退货：从已完成的采购入库单退货，扣库存、回退实收、冲减暂估
create table if not exists purchase_return (
    id bigint generated always as identity primary key,
    return_no varchar(32) not null unique,
    purchase_order_id bigint not null references purchase_order(id),
    inbound_order_id bigint not null references inbound_order(id),
    supplier_id bigint not null references supplier(id),
    warehouse_id bigint not null references warehouse(id),
    status varchar(16) not null default 'DRAFT',
    return_date date not null default current_date,
    total_qty numeric(18,4) not null default 0,
    total_amount numeric(18,2) not null default 0,
    reason varchar(255) not null,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    completed_by varchar(64),
    completed_at timestamptz,
    constraint ck_preturn_status check (status in ('DRAFT', 'COMPLETED', 'CANCELLED')),
    constraint ck_preturn_qty check (total_qty >= 0),
    constraint ck_preturn_amount check (total_amount >= 0)
);
comment on table purchase_return is '采购退货单：按入库单明细退货，提交时扣库存、回退实收、冲减暂估';

create table if not exists purchase_return_item (
    id bigint generated always as identity primary key,
    return_id bigint not null references purchase_return(id) on delete cascade,
    inbound_item_id bigint not null references inbound_order_item(id),
    purchase_order_item_id bigint references purchase_order_item(id),
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    batch_no varchar(64),
    price numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    constraint ck_preturn_item_qty check (qty > 0),
    constraint ck_preturn_item_amount check (amount >= 0)
);

create index if not exists idx_preturn_order on purchase_return (purchase_order_id);
create index if not exists idx_preturn_inbound on purchase_return (inbound_order_id);
create index if not exists idx_preturn_supplier on purchase_return (supplier_id, status);
create index if not exists idx_preturn_item_return on purchase_return_item (return_id);
create index if not exists idx_preturn_item_inbound_item on purchase_return_item (inbound_item_id);

-- 3. 编码规则与权限
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle)
values ('PRT', '采购退货单', 'PRT', 'yyyyMMdd', '', 4, 'DAY')
on conflict (biz_type) do nothing;

insert into sys_role_permission (role_id, permission) values
(1, 'warehouse:inbound_free'), (4, 'warehouse:inbound_free'),
(1, 'purchase:return'), (3, 'purchase:return'), (4, 'purchase:return'),
(1, 'preturn'), (3, 'preturn'), (4, 'preturn')
on conflict do nothing;
