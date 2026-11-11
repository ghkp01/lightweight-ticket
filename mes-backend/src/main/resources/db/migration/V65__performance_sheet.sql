-- 绩效核算：月度绩效单
--
-- 口径说明（与 PerformanceService 里的注释保持一致）：
--   * 系统算得出来的：报工量、合格量、不良率、标准工时、计件工资
--   * 系统算不出来的：实际工时 —— 由车间按人按期间录入，效率 = 标准工时 ÷ 实际工时
--   * 绩效系数 = 效率系数 × 合格率系数（分段规则见 PerformanceService，单位置 1.0 表示不奖惩）
--   * 绩效工资 = 计件工资 × 绩效系数
--
-- 为什么把「实际工时」留成人工字段：系统只记报工量，不记刷卡/排班考勤；
-- 拿标准工时反推效率是自欺欺人，所以宁可留一个空列让车间填，也不编一个假数据。

create table performance_sheet (
    id bigint generated always as identity primary key,
    period varchar(7) not null,                 -- yyyy-MM
    worker varchar(64) not null,
    work_orders int not null default 0,
    report_qty numeric(18,4) not null default 0,
    qualified_qty numeric(18,4) not null default 0,
    defect_qty numeric(18,4) not null default 0,
    standard_minutes numeric(18,2) not null default 0,
    actual_minutes numeric(18,2),               -- 人工录入；空表示车间还没填
    piece_wage numeric(18,2) not null default 0,
    efficiency numeric(9,4),                    -- 标准工时 ÷ 实际工时
    yield_rate numeric(9,4),                    -- 合格量 ÷ 报工量
    score numeric(9,4) not null default 1,      -- 绩效系数
    wage numeric(18,2) not null default 0,      -- 绩效工资 = 计件工资 × 系数
    status varchar(16) not null default 'DRAFT',
    remark varchar(255),
    reject_reason varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    submitted_at timestamptz,
    approved_by varchar(64),
    approved_at timestamptz,
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint uk_perf_period_worker unique (period, worker),
    constraint ck_perf_status check (status in ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'))
);
create index idx_perf_period on performance_sheet(period, status);

-- 绩效菜单与审核权限：管理员、计划可见/可审
insert into sys_role_permission (role_id, permission) values
(1, 'perf'), (3, 'perf'), (1, 'perf:approve'), (3, 'perf:approve')
on conflict do nothing;
