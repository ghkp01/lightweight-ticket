-- 后台定时任务：执行日志 + 权限
create table if not exists sys_job_log (
    id bigint generated always as identity primary key,
    job_code varchar(64) not null,
    job_name varchar(128) not null,
    trigger_type varchar(16) not null default 'SCHEDULED',
    status varchar(16) not null,
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    duration_ms bigint not null default 0,
    affected int not null default 0,
    message varchar(1000),
    operator varchar(64)
);

create index if not exists idx_job_log_code on sys_job_log (job_code, started_at desc);
create index if not exists idx_job_log_started on sys_job_log (started_at desc);

comment on table sys_job_log is '后台定时任务执行记录：定时触发与手工触发都留痕';
comment on column sys_job_log.affected is '本次处理条数，由各任务自己定义（通知的订单数、重算的工单数等）';

insert into sys_role_permission (role_id, permission) values
(1, 'system_job')
on conflict do nothing;

insert into sys_config (config_key, config_value, value_type, title, group_name, remark)
values ('job.enabled', 'true', 'BOOLEAN', '启用后台定时任务', '任务',
        '关闭后定时任务不再自动执行，仍可在「定时任务」页手工执行')
on conflict (config_key) do nothing;
