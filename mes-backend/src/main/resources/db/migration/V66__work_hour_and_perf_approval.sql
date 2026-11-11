-- 工时填报（绩效的实际工时来源）+ 绩效接审批流
--
-- 之前绩效单的「实际工时」是手填的单个数字，一个月一个总数，出了问题没法回溯。
-- 现在改成按天填工时（一天可以填多条：上午 / 下午 / 加班），绩效生成时按月汇总，
-- 明细留在 work_hour 里，谁填的、哪天填的都能查。
--
-- 兼容：绩效单仍然保留 actual_minutes 字段 —— 工时表里没有这个人时，沿用手工录入的值，
-- 并用 minutes_source 标明来源（HOUR_SHEET / MANUAL），避免「明明填了工时却显示没填」。

create table work_hour (
    id bigint generated always as identity primary key,
    work_date date not null,
    worker varchar(64) not null,
    minutes numeric(18,2) not null,
    source varchar(16) not null default 'MANUAL',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint ck_work_hour_minutes check (minutes > 0 and minutes <= 1440),
    constraint ck_work_hour_source check (source in ('MANUAL', 'ATTENDANCE', 'IMPORT'))
);
create index idx_work_hour_worker on work_hour(worker, work_date);
create index idx_work_hour_date on work_hour(work_date);

-- 绩效单：工时来源 + 审批实例（配置了 PERFORMANCE 审批流时才有值）
alter table performance_sheet add column if not exists minutes_source varchar(16);
alter table performance_sheet add column if not exists approval_instance_id bigint;
