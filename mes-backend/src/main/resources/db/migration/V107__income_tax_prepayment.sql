-- 企业所得税季度预缴：按累计会计利润计算，扣除本年以前季度已预缴

create table tax_prepayment (
    id bigint generated always as identity primary key,
    tax_year int not null,
    quarter int not null,
    period_start date not null,
    period_end date not null,
    tax_rate numeric(6,4) not null default 0.2500,
    ytd_profit numeric(18,2) not null default 0,
    ytd_tax numeric(18,2) not null default 0,
    prepaid_before numeric(18,2) not null default 0,
    current_prepaid numeric(18,2) not null default 0,
    status varchar(12) not null default 'DRAFT',
    filed_at timestamptz,
    paid_at timestamptz,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint uk_tax_prepayment unique (tax_year, quarter),
    constraint ck_tax_prepayment_quarter check (quarter between 1 and 4),
    constraint ck_tax_prepayment_status check (status in ('DRAFT', 'FILED', 'PAID')),
    constraint ck_tax_prepayment_rate check (tax_rate >= 0 and tax_rate <= 1)
);
comment on table tax_prepayment is '企业所得税季度预缴：累计利润 × 税率 - 本年以前季度已预缴';
create index ix_tax_prepayment_year on tax_prepayment (tax_year);
