-- 系统管理 + 单据化仓储

alter table sys_user add column phone varchar(20);
alter table sys_user add column email varchar(128);
alter table sys_user add column warehouse_id bigint references warehouse(id);
create unique index uk_sys_user_phone on sys_user(phone) where phone is not null;

create table sys_role (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(64) not null,
    description varchar(255),
    data_scope varchar(16) not null default 'ALL',
    builtin boolean not null default false,
    created_at timestamptz not null default now(),
    constraint ck_role_scope check (data_scope in ('ALL','SELF','WAREHOUSE'))
);

create table sys_role_permission (
    role_id bigint not null references sys_role(id) on delete cascade,
    permission varchar(64) not null,
    primary key (role_id, permission)
);

insert into sys_role (id, code, name, description, data_scope, builtin) overriding system value values
(1, 'ADMIN', '系统管理员', '拥有全部菜单与数据权限', 'ALL', true),
(2, 'SALES', '销售', '客户、销售订单、发货跟踪、退货申请', 'SELF', true),
(3, 'PLANNER', '计划', '销售订单、生产工单、领料与报工', 'ALL', true),
(4, 'WAREHOUSE', '仓库', '出入库、调拨、盘点、发货、库存', 'WAREHOUSE', true),
(5, 'QC', '质检', '来料、过程、完工检验与退货判定', 'ALL', true);

insert into sys_role_permission (role_id, permission) values
(1, 'dashboard'), (1, 'sales'), (1, 'production'), (1, 'shipments'), (1, 'inventory'),
(1, 'inbound'), (1, 'outbound'), (1, 'transfer'), (1, 'stocktake'), (1, 'quality'),
(1, 'returns'), (1, 'customers'), (1, 'products'), (1, 'warehouses'), (1, 'system_user'),
(1, 'system_role'),
(2, 'dashboard'), (2, 'sales'), (2, 'shipments'), (2, 'returns'), (2, 'customers'), (2, 'products'),
(3, 'dashboard'), (3, 'sales'), (3, 'production'), (3, 'inventory'), (3, 'products'), (3, 'warehouses'),
(4, 'dashboard'), (4, 'shipments'), (4, 'inventory'), (4, 'inbound'), (4, 'outbound'),
(4, 'transfer'), (4, 'stocktake'), (4, 'products'),
(5, 'dashboard'), (5, 'quality'), (5, 'returns'), (5, 'products'), (5, 'inventory');

update sys_user set phone = '13800000000', email = 'admin@mes.local' where username = 'admin';
update sys_user set phone = '13800000001' where username = 'sales';
update sys_user set phone = '13800000002' where username = 'planner';
update sys_user set phone = '13800000003', warehouse_id = 1 where username = 'warehouse';
update sys_user set phone = '13800000004' where username = 'qc';

create table inbound_order (
    id bigint generated always as identity primary key,
    inbound_no varchar(32) not null unique,
    type varchar(16) not null default 'PURCHASE',
    warehouse_id bigint not null references warehouse(id),
    supplier varchar(128),
    status varchar(24) not null default 'DRAFT',
    need_inspection boolean not null default true,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_inbound_type check (type in ('PURCHASE','RETURN','OTHER')),
    constraint ck_inbound_status check (status in ('DRAFT','PENDING_QC','COMPLETED','CANCELLED'))
);

create table inbound_order_item (
    id bigint generated always as identity primary key,
    inbound_id bigint not null references inbound_order(id) on delete cascade,
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    batch_no varchar(64)
);

create table outbound_order (
    id bigint generated always as identity primary key,
    outbound_no varchar(32) not null unique,
    type varchar(16) not null default 'MANUAL',
    warehouse_id bigint not null references warehouse(id),
    status varchar(24) not null default 'DRAFT',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_outbound_type check (type in ('MANUAL','SCRAP','OTHER')),
    constraint ck_outbound_status check (status in ('DRAFT','COMPLETED','CANCELLED'))
);

create table outbound_order_item (
    id bigint generated always as identity primary key,
    outbound_id bigint not null references outbound_order(id) on delete cascade,
    product_id bigint not null references product(id),
    qty numeric(18,4) not null
);

create table transfer_order (
    id bigint generated always as identity primary key,
    transfer_no varchar(32) not null unique,
    from_warehouse_id bigint not null references warehouse(id),
    to_warehouse_id bigint not null references warehouse(id),
    status varchar(24) not null default 'DRAFT',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_transfer_status check (status in ('DRAFT','COMPLETED','CANCELLED'))
);

create table transfer_order_item (
    id bigint generated always as identity primary key,
    transfer_id bigint not null references transfer_order(id) on delete cascade,
    product_id bigint not null references product(id),
    qty numeric(18,4) not null
);

create table stocktake (
    id bigint generated always as identity primary key,
    stocktake_no varchar(32) not null unique,
    warehouse_id bigint not null references warehouse(id),
    status varchar(24) not null default 'DRAFT',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_stocktake_status check (status in ('DRAFT','COMPLETED','CANCELLED'))
);

create table stocktake_item (
    id bigint generated always as identity primary key,
    stocktake_id bigint not null references stocktake(id) on delete cascade,
    product_id bigint not null references product(id),
    book_qty numeric(18,4) not null,
    actual_qty numeric(18,4) not null,
    diff_qty numeric(18,4) not null default 0
);

alter table inspection add column inbound_order_id bigint references inbound_order(id);

create index idx_inbound_status on inbound_order(status);
create index idx_outbound_status on outbound_order(status);
create index idx_stocktake_status on stocktake(status);
create index idx_inspection_inbound on inspection(inbound_order_id);

insert into inbound_order (id, inbound_no, type, warehouse_id, supplier, status, need_inspection, remark, created_by, created_at) overriding system value values
(1, 'IN' || to_char(current_date - 1, 'YYYYMMDD') || '0001', 'PURCHASE', 1, '江苏铝业有限公司', 'PENDING_QC', true, '型材到货，待来料检验', '仓库-赵磊', now() - interval '1 day');

insert into inbound_order_item (id, inbound_id, product_id, qty, batch_no) overriding system value values
(1, 1, 3, 300, 'B' || to_char(current_date, 'YYYYMMDD') || '01');

select setval(pg_get_serial_sequence('sys_role', 'id'), (select max(id) from sys_role));
select setval(pg_get_serial_sequence('inbound_order', 'id'), (select max(id) from inbound_order));
select setval(pg_get_serial_sequence('inbound_order_item', 'id'), (select max(id) from inbound_order_item));
