-- 企业所得税汇算清缴：年度会计利润 + 纳税调整 + 弥补亏损 + 应补/应退

insert into fin_account (code, name, type, role) values
    ('6801', '所得税费用', 'EXPENSE', 'INCOME_TAX_EXPENSE'),
    ('222105', '应交税费-应交所得税', 'LIABILITY', 'INCOME_TAX_PAYABLE')
on conflict (code) do nothing;

create table tax_income_settlement (
    id bigint generated always as identity primary key,
    tax_year int not null unique,
    accounting_profit numeric(18,2) not null default 0,
    adjustment_add numeric(18,2) not null default 0,
    adjustment_deduct numeric(18,2) not null default 0,
    loss_carryforward numeric(18,2) not null default 0,
    taxable_income numeric(18,2) not null default 0,
    tax_rate numeric(6,4) not null default 0.2500,
    tax_payable numeric(18,2) not null default 0,
    tax_relief numeric(18,2) not null default 0,
    prepaid_tax numeric(18,2) not null default 0,
    payable_tax numeric(18,2) not null default 0,
    status varchar(12) not null default 'DRAFT',
    filed_at timestamptz,
    paid_at timestamptz,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_income_settlement_status check (status in ('DRAFT', 'FILED', 'PAID')),
    constraint ck_income_settlement_rate check (tax_rate >= 0 and tax_rate <= 1)
);
comment on table tax_income_settlement is '企业所得税年度汇算清缴：一年一条，应补税额为负表示应退';
create index ix_income_settlement_year on tax_income_settlement (tax_year);

create table tax_adjustment (
    id bigint generated always as identity primary key,
    settlement_id bigint not null references tax_income_settlement(id) on delete cascade,
    adjust_type varchar(8) not null,
    item varchar(128) not null,
    amount numeric(18,2) not null,
    remark varchar(255),
    constraint ck_tax_adjustment_type check (adjust_type in ('ADD', 'DEDUCT')),
    constraint ck_tax_adjustment_amount check (amount > 0)
);
comment on table tax_adjustment is '纳税调整明细：ADD 调增 / DEDUCT 调减';
create index ix_tax_adjustment_settlement on tax_adjustment (settlement_id);
