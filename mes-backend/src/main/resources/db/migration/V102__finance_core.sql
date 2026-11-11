-- 财务核心：总账（科目/期间/凭证）+ 应收（发票/收款/核销）+ 汇率
-- 口径：凭证按本位币（CNY）记账；外币单据保留原币金额与记账汇率

-- ---------------------------------------------------------------- 科目与期间

create table fin_account (
    code varchar(32) primary key,
    name varchar(64) not null,
    type varchar(16) not null,
    role varchar(24),
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    constraint ck_fin_account_type check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);
comment on table fin_account is '会计科目：role 标记自动记账用的角色科目，一角色一科目';
create unique index uk_fin_account_role on fin_account (role) where role is not null;

insert into fin_account (code, name, type, role) values
    ('1002', '银行存款', 'ASSET', 'BANK'),
    ('1122', '应收账款', 'ASSET', 'AR'),
    ('1405', '库存商品', 'ASSET', 'INVENTORY'),
    ('2202', '应付账款', 'LIABILITY', 'AP'),
    ('222101', '应交税费-应交增值税-销项税额', 'LIABILITY', 'OUTPUT_TAX'),
    ('222102', '应交税费-应交增值税-进项税额', 'LIABILITY', 'INPUT_TAX'),
    ('6001', '主营业务收入', 'REVENUE', 'REVENUE'),
    ('6401', '主营业务成本', 'EXPENSE', 'COST')
on conflict (code) do nothing;

create table fin_period (
    period varchar(7) primary key,
    status varchar(8) not null default 'OPEN',
    closed_by varchar(64),
    closed_at timestamptz,
    constraint ck_fin_period_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_fin_period_format check (period ~ '^[0-9]{4}-[0-9]{2}$')
);
comment on table fin_period is '会计期间：没有记录视为 OPEN，关账后该期间禁止再记账';

create table fin_exchange_rate (
    id bigint generated always as identity primary key,
    currency varchar(8) not null,
    rate_date date not null,
    rate numeric(18,8) not null,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_fin_rate_currency check (currency in ('USD', 'EUR')),
    constraint ck_fin_rate_positive check (rate > 0),
    unique (currency, rate_date)
);
comment on table fin_exchange_rate is '外币记账汇率：1 外币 = rate 人民币，按单据日期取最近一条';

create sequence fin_manual_voucher_seq start 1;

-- ---------------------------------------------------------------- 凭证

create table fin_voucher (
    id bigint generated always as identity primary key,
    voucher_no varchar(32) not null unique,
    voucher_date date not null,
    period varchar(7) not null,
    biz_type varchar(24) not null,
    biz_id bigint not null,
    biz_no varchar(64),
    currency varchar(8) not null default 'CNY',
    exchange_rate numeric(18,8) not null default 1,
    summary varchar(255),
    status varchar(12) not null default 'POSTED',
    reversal_of bigint references fin_voucher(id),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_fin_voucher_status check (status in ('POSTED', 'REVERSED')),
    constraint uk_fin_voucher_biz unique (biz_type, biz_id)
);
comment on table fin_voucher is '记账凭证：业务单据自动生成，一张单据一张凭证；(biz_type, biz_id) 幂等';
create index ix_fin_voucher_period on fin_voucher (period, voucher_date);
create index ix_fin_voucher_date on fin_voucher (voucher_date);

create table fin_voucher_line (
    id bigint generated always as identity primary key,
    voucher_id bigint not null references fin_voucher(id) on delete cascade,
    line_no int not null,
    account_code varchar(32) not null references fin_account(code),
    summary varchar(255),
    debit numeric(18,2) not null default 0,
    credit numeric(18,2) not null default 0,
    constraint ck_fin_line_amount check (debit >= 0 and credit >= 0 and (debit = 0 or credit = 0))
);
create index ix_fin_voucher_line_voucher on fin_voucher_line (voucher_id);
create index ix_fin_voucher_line_account on fin_voucher_line (account_code);

-- ---------------------------------------------------------------- 应收

create table ar_invoice (
    id bigint generated always as identity primary key,
    invoice_no varchar(32) not null unique,
    invoice_type varchar(8) not null default 'NORMAL',
    customer_id bigint not null references customer(id),
    sales_order_id bigint references sales_order(id),
    shipment_id bigint references shipment(id),
    invoice_date date not null,
    due_date date,
    currency varchar(8) not null default 'CNY',
    exchange_rate numeric(18,8) not null default 1,
    tax_rate numeric(6,4) not null default 0.1300,
    price_includes_tax boolean not null default true,
    net_amount numeric(18,2) not null default 0,
    tax_amount numeric(18,2) not null default 0,
    total_amount numeric(18,2) not null default 0,
    base_net numeric(18,2) not null default 0,
    base_tax numeric(18,2) not null default 0,
    base_total numeric(18,2) not null default 0,
    received_amount numeric(18,2) not null default 0,
    status varchar(12) not null default 'ISSUED',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_ar_invoice_type check (invoice_type in ('NORMAL', 'CREDIT')),
    constraint ck_ar_invoice_status check (status in ('ISSUED', 'PARTIAL', 'PAID', 'CANCELLED')),
    constraint ck_ar_invoice_amount check (total_amount >= 0 and base_total >= 0 and received_amount >= 0),
    constraint ck_ar_invoice_currency check (currency in ('CNY', 'USD', 'EUR'))
);
comment on table ar_invoice is '销售发票：NORMAL 蓝字 / CREDIT 红字（退货冲减）；金额为原币，base_* 为本位币';
create unique index uk_ar_invoice_shipment on ar_invoice (shipment_id)
    where shipment_id is not null and status <> 'CANCELLED';
create index ix_ar_invoice_customer on ar_invoice (customer_id, status);
create index ix_ar_invoice_due on ar_invoice (due_date);

create table ar_invoice_item (
    id bigint generated always as identity primary key,
    invoice_id bigint not null references ar_invoice(id) on delete cascade,
    product_id bigint,
    qty numeric(18,4) not null default 0,
    price numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    constraint ck_ar_invoice_item_qty check (qty > 0),
    constraint ck_ar_invoice_item_amount check (amount >= 0)
);
create index ix_ar_invoice_item_invoice on ar_invoice_item (invoice_id);

create table ar_receipt (
    id bigint generated always as identity primary key,
    receipt_no varchar(32) not null unique,
    customer_id bigint not null references customer(id),
    receipt_date date not null,
    currency varchar(8) not null default 'CNY',
    exchange_rate numeric(18,8) not null default 1,
    amount numeric(18,2) not null,
    base_amount numeric(18,2) not null,
    method varchar(16) not null default 'BANK',
    reference_no varchar(64),
    status varchar(12) not null default 'POSTED',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_ar_receipt_status check (status in ('POSTED', 'CANCELLED')),
    constraint ck_ar_receipt_amount check (amount > 0),
    constraint ck_ar_receipt_method check (method in ('BANK', 'CASH', 'OTHER')),
    constraint ck_ar_receipt_currency check (currency in ('CNY', 'USD', 'EUR'))
);
create index ix_ar_receipt_customer on ar_receipt (customer_id, receipt_date);

create table ar_receipt_apply (
    id bigint generated always as identity primary key,
    receipt_id bigint not null references ar_receipt(id) on delete cascade,
    invoice_id bigint not null references ar_invoice(id),
    amount numeric(18,2) not null,
    base_amount numeric(18,2) not null,
    constraint ck_ar_apply_amount check (amount > 0),
    unique (receipt_id, invoice_id)
);
create index ix_ar_apply_invoice on ar_receipt_apply (invoice_id);

-- ---------------------------------------------------------------- 角色与权限

insert into sys_role (code, name, description, data_scope, builtin) values
    ('FINANCE', '财务', '应收、收款核销、总账凭证与期间关账', 'ALL', true)
on conflict (code) do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values
    ('finance'), ('finance:ar'), ('finance:invoice'), ('finance:receipt'),
    ('finance:gl'), ('finance:period'), ('finance:rate'), ('finance:sync')) as p(permission)
where r.code in ('ADMIN', 'FINANCE')
on conflict do nothing;
