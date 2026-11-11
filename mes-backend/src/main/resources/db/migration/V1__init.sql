-- MES 初始化表结构（PostgreSQL）
create table customer (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(128) not null,
    contact varchar(64),
    phone varchar(32),
    address varchar(255),
    level varchar(16) default 'A',
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table product (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(128) not null,
    spec varchar(128),
    category varchar(16) not null,
    unit varchar(16) not null,
    price numeric(18,4) not null default 0,
    cost numeric(18,4) not null default 0,
    safety_stock numeric(18,4) not null default 0,
    loss_rate numeric(6,4) not null default 0,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    constraint ck_product_category check (category in ('RAW','SEMI','FINISHED'))
);

create table bom_item (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id),
    material_id bigint not null references product(id),
    qty numeric(18,4) not null,
    constraint uk_bom unique (product_id, material_id)
);

create table warehouse (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(64) not null,
    type varchar(16) not null,
    enabled boolean not null default true
);

create table sales_order (
    id bigint generated always as identity primary key,
    order_no varchar(32) not null unique,
    customer_id bigint not null references customer(id),
    order_date date not null,
    delivery_date date,
    status varchar(24) not null default 'DRAFT',
    total_qty numeric(18,4) not null default 0,
    total_amount numeric(18,2) not null default 0,
    remark varchar(500),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table sales_order_item (
    id bigint generated always as identity primary key,
    order_id bigint not null references sales_order(id),
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    price numeric(18,4) not null,
    amount numeric(18,2) not null,
    shipped_qty numeric(18,4) not null default 0
);

create table work_order (
    id bigint generated always as identity primary key,
    work_no varchar(32) not null unique,
    sales_order_id bigint references sales_order(id),
    sales_order_item_id bigint references sales_order_item(id),
    product_id bigint not null references product(id),
    plan_qty numeric(18,4) not null,
    done_qty numeric(18,4) not null default 0,
    qualified_qty numeric(18,4) not null default 0,
    status varchar(24) not null default 'DRAFT',
    plan_start date,
    plan_end date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table work_order_operation (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id),
    seq int not null,
    name varchar(64) not null,
    plan_qty numeric(18,4) not null,
    done_qty numeric(18,4) not null default 0,
    status varchar(24) not null default 'PENDING',
    constraint uk_wo_op unique (work_order_id, seq)
);

create table work_report (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id),
    operation_id bigint not null references work_order_operation(id),
    qty numeric(18,4) not null,
    qualified_qty numeric(18,4) not null,
    worker varchar(64),
    remark varchar(255),
    reported_at timestamptz not null default now()
);

create table inventory (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id),
    warehouse_id bigint not null references warehouse(id),
    qty numeric(18,4) not null default 0,
    locked_qty numeric(18,4) not null default 0,
    version bigint not null default 0,
    constraint uk_inventory unique (product_id, warehouse_id)
);

create table stock_txn (
    id bigint generated always as identity primary key,
    txn_no varchar(32) not null unique,
    product_id bigint not null references product(id),
    warehouse_id bigint not null references warehouse(id),
    txn_type varchar(16) not null,
    qty numeric(18,4) not null,
    ref_type varchar(16),
    ref_no varchar(32),
    operator varchar(64),
    remark varchar(255),
    created_at timestamptz not null default now()
);

create table shipment (
    id bigint generated always as identity primary key,
    shipment_no varchar(32) not null unique,
    sales_order_id bigint not null references sales_order(id),
    customer_id bigint not null references customer(id),
    status varchar(24) not null default 'DRAFT',
    carrier varchar(64),
    tracking_no varchar(64),
    address varchar(255),
    shipped_at timestamptz,
    signed_at timestamptz,
    created_at timestamptz not null default now(),
    version bigint not null default 0
);

create table shipment_item (
    id bigint generated always as identity primary key,
    shipment_id bigint not null references shipment(id),
    sales_order_item_id bigint not null references sales_order_item(id),
    product_id bigint not null references product(id),
    qty numeric(18,4) not null
);

create table inspection (
    id bigint generated always as identity primary key,
    inspection_no varchar(32) not null unique,
    type varchar(16) not null,
    ref_type varchar(16),
    ref_no varchar(32),
    work_order_id bigint references work_order(id),
    product_id bigint not null references product(id),
    inspect_qty numeric(18,4) not null,
    defect_qty numeric(18,4) not null default 0,
    result varchar(16) not null default 'PENDING',
    inspector varchar(64),
    inspected_at timestamptz,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_inspection_type check (type in ('INCOMING','PROCESS','FINAL')),
    constraint ck_inspection_result check (result in ('PENDING','PASS','FAIL'))
);

create table return_order (
    id bigint generated always as identity primary key,
    return_no varchar(32) not null unique,
    sales_order_id bigint not null references sales_order(id),
    customer_id bigint not null references customer(id),
    status varchar(24) not null default 'PENDING',
    reason varchar(255),
    disposition varchar(16) not null,
    total_qty numeric(18,4) not null default 0,
    processed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_return_disposition check (disposition in ('REWORK','SCRAP','RESTOCK'))
);

create table return_item (
    id bigint generated always as identity primary key,
    return_id bigint not null references return_order(id),
    product_id bigint not null references product(id),
    qty numeric(18,4) not null
);

create table sys_user (
    id bigint generated always as identity primary key,
    username varchar(64) not null unique,
    password varchar(128) not null,
    real_name varchar(64) not null,
    role varchar(24) not null,
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table code_seq (
    prefix varchar(16) not null,
    seq_date date not null,
    seq int not null,
    primary key (prefix, seq_date)
);

create index idx_so_status on sales_order(status);
create index idx_so_customer on sales_order(customer_id);
create index idx_wo_status on work_order(status);
create index idx_wo_order on work_order(sales_order_id);
create index idx_txn_product on stock_txn(product_id, created_at desc);
create index idx_txn_ref on stock_txn(ref_type, ref_no);
create index idx_inventory_product on inventory(product_id);
