-- 发票与应付：收货自动暂估、发票登记、三单匹配、付款与账龄

alter table supplier add column if not exists credit_days int not null default 30;
comment on column supplier.credit_days is '账期天数，用来算发票到期日';

create table ap_accrual (
    id bigint generated always as identity primary key,
    accrual_no varchar(32) not null unique,
    purchase_order_id bigint not null references purchase_order(id),
    inbound_order_id bigint not null unique references inbound_order(id),
    supplier_id bigint not null references supplier(id),
    accrual_date date not null default current_date,
    amount numeric(18,2) not null default 0,
    matched_amount numeric(18,2) not null default 0,
    status varchar(16) not null default 'OPEN',
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_accrual_status check (status in ('OPEN', 'PARTIAL', 'MATCHED')),
    constraint ck_accrual_amount check (amount >= 0 and matched_amount >= 0)
);
comment on table ap_accrual is '暂估应付：采购入库完成即生成，发票匹配后冲回';
create index idx_accrual_supplier on ap_accrual (supplier_id, status);

create table ap_accrual_item (
    id bigint generated always as identity primary key,
    accrual_id bigint not null references ap_accrual(id) on delete cascade,
    purchase_order_item_id bigint not null references purchase_order_item(id),
    qty numeric(18,4) not null,
    amount numeric(18,2) not null,
    matched_qty numeric(18,4) not null default 0,
    matched_amount numeric(18,2) not null default 0,
    constraint ck_accrual_item_qty check (qty >= 0),
    constraint ck_accrual_item_amount check (amount >= 0)
);
create index idx_accrual_item_po on ap_accrual_item (purchase_order_item_id);

create table ap_invoice (
    id bigint generated always as identity primary key,
    invoice_no varchar(64) not null unique,
    invoice_type varchar(16) not null default 'VAT_SPECIAL',
    supplier_id bigint not null references supplier(id),
    purchase_order_id bigint references purchase_order(id),
    invoice_date date not null,
    tax_rate numeric(6,3) not null default 13,
    net_amount numeric(18,2) not null default 0,
    tax_amount numeric(18,2) not null default 0,
    gross_amount numeric(18,2) not null default 0,
    paid_amount numeric(18,2) not null default 0,
    difference_amount numeric(18,2) not null default 0,
    difference_note varchar(255),
    status varchar(16) not null default 'DRAFT',
    due_date date,
    matched_by varchar(64),
    matched_at timestamptz,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_invoice_type check (invoice_type in ('VAT_SPECIAL', 'VAT_NORMAL', 'RECEIPT')),
    constraint ck_invoice_status check (status in ('DRAFT', 'MATCHED', 'PARTIAL', 'PAID', 'CANCELLED')),
    constraint ck_invoice_amount check (gross_amount >= 0 and paid_amount >= 0)
);
comment on table ap_invoice is '采购发票：登记税额与价税合计，匹配暂估后转正式应付';
comment on column ap_invoice.status is 'DRAFT 待匹配 / MATCHED 已匹配待付款 / PARTIAL 部分付款 / PAID 已付清 / CANCELLED 已作废';
create index idx_invoice_supplier on ap_invoice (supplier_id, status);
create index idx_invoice_due on ap_invoice (due_date);

create table ap_invoice_item (
    id bigint generated always as identity primary key,
    invoice_id bigint not null references ap_invoice(id) on delete cascade,
    purchase_order_item_id bigint not null references purchase_order_item(id),
    qty numeric(18,4) not null,
    price numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    matched_qty numeric(18,4) not null default 0,
    remark varchar(255),
    constraint ck_invoice_item_qty check (qty > 0),
    constraint ck_invoice_item_price check (price >= 0)
);
create index idx_invoice_item_invoice on ap_invoice_item (invoice_id);
create index idx_invoice_item_po on ap_invoice_item (purchase_order_item_id);

create table ap_invoice_match (
    id bigint generated always as identity primary key,
    invoice_id bigint not null references ap_invoice(id) on delete cascade,
    invoice_item_id bigint not null references ap_invoice_item(id) on delete cascade,
    accrual_item_id bigint not null references ap_accrual_item(id),
    qty numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    created_at timestamptz not null default now()
);
create index idx_invoice_match_invoice on ap_invoice_match (invoice_id);
create index idx_invoice_match_accrual on ap_invoice_match (accrual_item_id);

create table ap_payment (
    id bigint generated always as identity primary key,
    payment_no varchar(32) not null unique,
    invoice_id bigint not null references ap_invoice(id),
    supplier_id bigint not null references supplier(id),
    pay_date date not null default current_date,
    amount numeric(18,2) not null,
    method varchar(16) not null default 'BANK',
    reference_no varchar(64),
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_payment_amount check (amount > 0),
    constraint ck_payment_method check (method in ('BANK', 'CASH', 'OTHER'))
);
create index idx_payment_invoice on ap_payment (invoice_id);
create index idx_payment_supplier on ap_payment (supplier_id);

insert into sys_role_permission (role_id, permission) values
(1, 'ap'), (3, 'ap'),
(1, 'ap:invoice'), (3, 'ap:invoice'),
(1, 'ap:match'), (3, 'ap:match'),
(1, 'ap:payment'), (3, 'ap:payment')
on conflict do nothing;

-- 历史采购入库补暂估：只补有采购单、已完成、且还没暂估的入库单
insert into ap_accrual (accrual_no, purchase_order_id, inbound_order_id, supplier_id,
                        accrual_date, amount, matched_amount, status, remark)
select 'AC' || io.inbound_no, io.purchase_order_id, io.id, io.supplier_id,
       coalesce(io.completed_at::date, current_date),
       coalesce(sum(round(i.qty * poi.price, 2)), 0), 0, 'OPEN', '历史采购入库自动暂估'
from inbound_order io
join inbound_order_item i on i.inbound_id = io.id and i.purchase_item_id is not null
join purchase_order_item poi on poi.id = i.purchase_item_id
where io.purchase_order_id is not null
  and io.status = 'COMPLETED'
  and io.supplier_id is not null
  and not exists (select 1 from ap_accrual a where a.inbound_order_id = io.id)
group by io.id, io.inbound_no, io.purchase_order_id, io.supplier_id, io.completed_at
having coalesce(sum(round(i.qty * poi.price, 2)), 0) > 0;

insert into ap_accrual_item (accrual_id, purchase_order_item_id, qty, amount)
select a.id, i.purchase_item_id, i.qty, round(i.qty * poi.price, 2)
from ap_accrual a
join inbound_order_item i on i.inbound_id = a.inbound_order_id and i.purchase_item_id is not null
join purchase_order_item poi on poi.id = i.purchase_item_id
where not exists (select 1 from ap_accrual_item x where x.accrual_id = a.id);
