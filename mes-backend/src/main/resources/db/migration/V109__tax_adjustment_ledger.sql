-- 纳税调整跨年台账：记录每年调整明细与广告费结转余额

alter table tax_adjustment add column if not exists carryforward_balance numeric(18,2) not null default 0;
comment on column tax_adjustment.carryforward_balance is '本笔调整后该规则的结转余额（目前仅广告费使用）';

create table tax_adjustment_ledger (
    id bigint generated always as identity primary key,
    tax_year int not null,
    settlement_id bigint not null references tax_income_settlement(id) on delete cascade,
    rule_code varchar(32),
    item varchar(128) not null,
    adjust_type varchar(8) not null,
    amount numeric(18,2) not null default 0,
    base_amount numeric(18,2) not null default 0,
    limit_amount numeric(18,2) not null default 0,
    carryforward_balance numeric(18,2) not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_tax_ledger_type check (adjust_type in ('ADD', 'DEDUCT')),
    constraint uk_tax_ledger unique (settlement_id, item, adjust_type)
);
comment on table tax_adjustment_ledger is '纳税调整跨年台账：申报时落账，广告费结转余额按年传递';
create index ix_tax_ledger_year on tax_adjustment_ledger (tax_year);
create index ix_tax_ledger_rule on tax_adjustment_ledger (rule_code, tax_year);
