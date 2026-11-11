-- 固定资产与税务申报

-- ---------------------------------------------------------------- 科目

insert into fin_account (code, name, type, role) values
    ('1601', '固定资产', 'ASSET', 'FIXED_ASSET'),
    ('1602', '累计折旧', 'ASSET', 'ACCUM_DEPRECIATION'),
    ('6602', '管理费用-折旧费', 'EXPENSE', 'DEPRECIATION_EXPENSE'),
    ('6115', '资产处置损益', 'EXPENSE', 'ASSET_DISPOSAL'),
    ('222103', '应交税费-未交增值税', 'LIABILITY', 'VAT_PAYABLE'),
    ('222104', '应交税费-应交附加税', 'LIABILITY', 'SURTAX_PAYABLE'),
    ('6403', '税金及附加', 'EXPENSE', 'SURTAX_EXPENSE')
on conflict (code) do nothing;

-- ---------------------------------------------------------------- 固定资产

create table fa_asset (
    id bigint generated always as identity primary key,
    asset_no varchar(32) not null unique,
    name varchar(128) not null,
    category varchar(64),
    location varchar(128),
    department varchar(64),
    acquisition_date date not null,
    original_value numeric(18,2) not null,
    residual_rate numeric(6,4) not null default 0.0500,
    useful_life_months int not null,
    depreciation_start date not null,
    accumulated_depreciation numeric(18,2) not null default 0,
    status varchar(12) not null default 'IN_USE',
    disposal_date date,
    disposal_amount numeric(18,2),
    disposal_gain_loss numeric(18,2),
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_fa_original check (original_value >= 0),
    constraint ck_fa_residual check (residual_rate >= 0 and residual_rate < 1),
    constraint ck_fa_life check (useful_life_months > 0),
    constraint ck_fa_status check (status in ('IN_USE', 'DISPOSED'))
);
comment on table fa_asset is '固定资产卡片：直线法折旧，残值率与使用月数决定月折旧额';
create index ix_fa_asset_status on fa_asset (status);

create table fa_depreciation (
    id bigint generated always as identity primary key,
    asset_id bigint not null references fa_asset(id),
    period varchar(7) not null,
    amount numeric(18,2) not null,
    accumulated_after numeric(18,2) not null,
    voucher_id bigint,
    created_at timestamptz not null default now(),
    constraint uk_fa_depreciation unique (asset_id, period),
    constraint ck_fa_depreciation_amount check (amount > 0)
);
comment on table fa_depreciation is '固定资产折旧明细：一台资产一个期间一条，重复计提会被唯一键拦住';
create index ix_fa_depreciation_period on fa_depreciation (period);

create sequence fa_depreciation_run_seq start 1;

-- ---------------------------------------------------------------- 税务申报

create table tax_filing (
    id bigint generated always as identity primary key,
    period varchar(7) not null,
    tax_type varchar(16) not null default 'VAT',
    output_tax numeric(18,2) not null default 0,
    input_tax numeric(18,2) not null default 0,
    vat_payable numeric(18,2) not null default 0,
    city_rate numeric(6,4) not null default 0.0700,
    edu_rate numeric(6,4) not null default 0.0300,
    local_edu_rate numeric(6,4) not null default 0.0200,
    city_tax numeric(18,2) not null default 0,
    edu_tax numeric(18,2) not null default 0,
    local_edu_tax numeric(18,2) not null default 0,
    total_surtax numeric(18,2) not null default 0,
    total_payable numeric(18,2) not null default 0,
    status varchar(12) not null default 'DRAFT',
    filed_at timestamptz,
    paid_at timestamptz,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint uk_tax_filing unique (period, tax_type),
    constraint ck_tax_status check (status in ('DRAFT', 'FILED', 'PAID')),
    constraint ck_tax_type check (tax_type in ('VAT'))
);
comment on table tax_filing is '税务申报：按期间汇总销项/进项，生成增值税与附加税申报表';
create index ix_tax_filing_period on tax_filing (period);

-- ---------------------------------------------------------------- 权限

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values
    ('finance:asset'), ('finance:asset:depreciate'), ('finance:tax')) as p(permission)
where r.code in ('ADMIN', 'FINANCE')
on conflict do nothing;
