-- 红字发票与退货对账：红字发票红冲蓝票、已付差额转待供应商退款、退货单回链红票

-- 1. 红字发票字段
alter table ap_invoice add column if not exists related_invoice_id bigint references ap_invoice(id);
alter table ap_invoice add column if not exists refundable_amount numeric(18,2) not null default 0;
comment on column ap_invoice.related_invoice_id is '红字发票对应的蓝字发票，红冲时按蓝票明细逐行回退';
comment on column ap_invoice.refundable_amount is '已付款超过红冲后应付的部分，即供应商应退回金额';

-- 红字发票金额为负，因此不能再约束 gross >= 0；已付与待退仍不能为负
alter table ap_invoice drop constraint if exists ck_invoice_amount;
alter table ap_invoice add constraint ck_invoice_amount
    check (paid_amount >= 0 and refundable_amount >= 0);

alter table ap_invoice drop constraint if exists ck_invoice_type;
alter table ap_invoice add constraint ck_invoice_type
    check (invoice_type in ('VAT_SPECIAL', 'VAT_NORMAL', 'RECEIPT', 'CREDIT'));

alter table ap_invoice drop constraint if exists ck_invoice_status;
alter table ap_invoice add constraint ck_invoice_status
    check (status in ('DRAFT', 'MATCHED', 'PARTIAL', 'PAID', 'CANCELLED', 'RED_REVERSED'));

alter table ap_invoice drop constraint if exists ck_invoice_credit_related;
alter table ap_invoice add constraint ck_invoice_credit_related
    check (invoice_type <> 'CREDIT' or related_invoice_id is not null);

-- 红字明细数量为负，约束由「必须大于 0」改为「不能为 0」
alter table ap_invoice_item drop constraint if exists ck_invoice_item_qty;
alter table ap_invoice_item add constraint ck_invoice_item_qty check (qty <> 0);

create index if not exists idx_invoice_related on ap_invoice (related_invoice_id);

-- 2. 供应商退款台账：红冲后已付款多于应付时，供应商把钱退回来
create table if not exists ap_refund (
    id bigint generated always as identity primary key,
    refund_no varchar(32) not null unique,
    invoice_id bigint not null references ap_invoice(id),
    supplier_id bigint not null references supplier(id),
    refund_date date not null default current_date,
    amount numeric(18,2) not null,
    method varchar(16) not null default 'BANK',
    reference_no varchar(64),
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_refund_amount check (amount > 0),
    constraint ck_refund_method check (method in ('BANK', 'CASH', 'OTHER'))
);
comment on table ap_refund is '供应商退款：红冲后已付金额大于应付时登记，冲减发票的待退款金额';
create index if not exists idx_refund_invoice on ap_refund (invoice_id);
create index if not exists idx_refund_supplier on ap_refund (supplier_id);

-- 3. 退货单回链红字发票
alter table purchase_return add column if not exists red_invoice_id bigint references ap_invoice(id);
comment on column purchase_return.red_invoice_id is '已开票后退货自动生成的红字发票';
create index if not exists idx_preturn_red_invoice on purchase_return (red_invoice_id);

-- 4. 编码规则与权限
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle)
values ('RF', '供应商退款', 'RF', 'yyyyMMdd', '', 4, 'DAY')
on conflict (biz_type) do nothing;

insert into sys_role_permission (role_id, permission) values
(1, 'ap:credit'), (3, 'ap:credit'),
(1, 'ap:refund'), (3, 'ap:refund')
on conflict do nothing;
