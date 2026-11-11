-- 企业所得税汇算增强：纳税调整规则、小型微利、研发辅助账、分支机构分摊

-- ---------------------------------------------------------------- 科目

insert into fin_account (code, name, type, role) values
    ('660201', '管理费用-业务招待费', 'EXPENSE', 'ENTERTAINMENT_EXPENSE'),
    ('660202', '管理费用-广告费和业务宣传费', 'EXPENSE', 'ADVERTISING_EXPENSE'),
    ('660203', '管理费用-罚款及滞纳金', 'EXPENSE', 'FINE_EXPENSE'),
    ('660204', '管理费用-研发费用', 'EXPENSE', 'RD_EXPENSE')
on conflict (code) do nothing;

-- ---------------------------------------------------------------- 纳税调整规则

create table tax_adjustment_rule (
    code varchar(32) primary key,
    name varchar(128) not null,
    adjust_type varchar(8) not null,
    method varchar(24) not null,
    expense_role varchar(32),
    ratio1 numeric(8,6),
    ratio2 numeric(8,6),
    enabled boolean not null default true,
    remark varchar(255),
    constraint ck_tax_rule_type check (adjust_type in ('ADD', 'DEDUCT')),
    constraint ck_tax_rule_method check (method in ('ENTERTAINMENT', 'ADVERTISING', 'FULL', 'RD', 'MANUAL'))
);
comment on table tax_adjustment_rule is '纳税调整规则：按科目发生额自动计算调增/调减';

insert into tax_adjustment_rule (code, name, adjust_type, method, expense_role, ratio1, ratio2, remark) values
    ('ENTERTAINMENT', '业务招待费', 'ADD', 'ENTERTAINMENT', 'ENTERTAINMENT_EXPENSE', 0.600000, 0.005000,
     '按发生额 60% 与营业收入 5‰ 孰低扣除，超出部分调增'),
    ('ADVERTISING', '广告费和业务宣传费', 'ADD', 'ADVERTISING', 'ADVERTISING_EXPENSE', 0.150000, null,
     '按营业收入 15% 限额扣除，超出部分调增'),
    ('FINE', '罚款、滞纳金', 'ADD', 'FULL', 'FINE_EXPENSE', null, null,
     '行政罚款、税收滞纳金不得税前扣除，全额调增'),
    ('RD_SUPER', '研发费用加计扣除', 'DEDUCT', 'RD', 'RD_EXPENSE', 1.000000, null,
     '按研发费用辅助账归集金额加计扣除，默认 100%')
on conflict (code) do nothing;

alter table tax_adjustment add column if not exists rule_code varchar(32);
alter table tax_adjustment add column if not exists base_amount numeric(18,2) not null default 0;
alter table tax_adjustment add column if not exists limit_amount numeric(18,2) not null default 0;
comment on column tax_adjustment.base_amount is '计算基数：科目发生额或研发辅助账金额';
comment on column tax_adjustment.limit_amount is '扣除限额，没有限额的规则为 0';

-- ---------------------------------------------------------------- 小型微利

alter table tax_income_settlement add column if not exists enterprise_type varchar(16) not null default 'GENERAL';
alter table tax_income_settlement add column if not exists employee_count int;
alter table tax_income_settlement add column if not exists total_assets numeric(18,2);
alter table tax_income_settlement add column if not exists small_profit boolean not null default false;
alter table tax_income_settlement add column if not exists small_relief numeric(18,2) not null default 0;
comment on column tax_income_settlement.enterprise_type is 'GENERAL 一般企业 / SMALL 小型微利企业';
comment on column tax_income_settlement.small_relief is '小型微利减免税额（25% 与 5% 的差额）';

-- ---------------------------------------------------------------- 分支机构

create table tax_branch (
    id bigint generated always as identity primary key,
    name varchar(128) not null,
    taxpayer_id varchar(64),
    share_ratio numeric(8,6) not null,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_tax_branch_ratio check (share_ratio > 0 and share_ratio <= 1)
);
comment on table tax_branch is '分支机构所得税分摊比例，总机构为剩余比例';

create table tax_branch_allocation (
    id bigint generated always as identity primary key,
    settlement_id bigint not null references tax_income_settlement(id) on delete cascade,
    branch_id bigint,
    branch_name varchar(128) not null,
    share_ratio numeric(8,6) not null,
    amount numeric(18,2) not null,
    constraint uk_tax_branch_allocation unique (settlement_id, branch_name)
);
create index ix_tax_branch_allocation_settlement on tax_branch_allocation (settlement_id);

-- ---------------------------------------------------------------- 研发费用辅助账

create table rd_project (
    id bigint generated always as identity primary key,
    project_no varchar(32) not null unique,
    name varchar(128) not null,
    tax_year int not null,
    responsible varchar(64),
    status varchar(12) not null default 'ACTIVE',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_rd_project_status check (status in ('ACTIVE', 'CLOSED'))
);
create index ix_rd_project_year on rd_project (tax_year);

create table rd_expense (
    id bigint generated always as identity primary key,
    project_id bigint not null references rd_project(id) on delete cascade,
    expense_date date not null,
    category varchar(24) not null,
    amount numeric(18,2) not null,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_rd_expense_category check (category in ('PERSONNEL', 'MATERIAL', 'DEPRECIATION', 'AMORTIZATION', 'OTHER')),
    constraint ck_rd_expense_amount check (amount > 0)
);
create index ix_rd_expense_project on rd_expense (project_id);
create index ix_rd_expense_date on rd_expense (expense_date);
