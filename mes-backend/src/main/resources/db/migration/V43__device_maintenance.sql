-- 设备点检与保养：点检项、点检任务与结果、保养计划与记录、故障记录
create table if not exists device_check_item (
    id bigint generated always as identity primary key,
    device_id bigint references work_center_device(id) on delete cascade,
    name varchar(128) not null,
    method varchar(32) not null default '目视',
    standard varchar(255),
    sort int not null default 0,
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

comment on table device_check_item is '点检项模板：device_id 为空表示所有设备通用';
comment on column device_check_item.method is '点检方法：目视 / 听音 / 手感 / 测量';

create index if not exists idx_check_item_device on device_check_item (device_id, enabled);

create table if not exists device_check_task (
    id bigint generated always as identity primary key,
    task_no varchar(32) not null unique,
    device_id bigint not null references work_center_device(id),
    plan_date date not null,
    status varchar(16) not null default 'PENDING',
    checker varchar(64),
    checked_at timestamptz,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_check_status check (status in ('PENDING', 'DONE', 'SKIPPED'))
);

create unique index if not exists uk_check_task_device_date on device_check_task (device_id, plan_date);
create index if not exists idx_check_task_date on device_check_task (plan_date, status);

comment on table device_check_task is '点检任务：每台设备每天一张，重复生成不新增（按设备+日期唯一）';

create table if not exists device_check_result (
    id bigint generated always as identity primary key,
    task_id bigint not null references device_check_task(id) on delete cascade,
    item_id bigint,
    item_name varchar(128) not null,
    method varchar(32),
    standard varchar(255),
    result varchar(8) not null default 'OK',
    remark varchar(255),
    constraint ck_check_result check (result in ('OK', 'NG'))
);

create index if not exists idx_check_result_task on device_check_result (task_id);

create table if not exists device_maintain_plan (
    id bigint generated always as identity primary key,
    device_id bigint not null references work_center_device(id) on delete cascade,
    name varchar(128) not null,
    cycle_days int not null default 30,
    last_date date,
    next_date date not null,
    standard varchar(255),
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    constraint ck_maintain_cycle check (cycle_days > 0)
);

create index if not exists idx_maintain_next on device_maintain_plan (next_date, enabled);

create table if not exists device_maintain_log (
    id bigint generated always as identity primary key,
    plan_id bigint references device_maintain_plan(id) on delete set null,
    device_id bigint not null references work_center_device(id),
    done_date date not null,
    worker varchar(64),
    result varchar(16) not null default 'DONE',
    cost numeric(12,2) not null default 0,
    remark varchar(500),
    created_at timestamptz not null default now()
);

create index if not exists idx_maintain_log_device on device_maintain_log (device_id, done_date desc);

create table if not exists device_fault (
    id bigint generated always as identity primary key,
    fault_no varchar(32) not null unique,
    device_id bigint not null references work_center_device(id),
    occurred_at timestamptz not null default now(),
    recovered_at timestamptz,
    symptom varchar(500) not null,
    cause varchar(500),
    action varchar(500),
    downtime_minutes int,
    reporter varchar(64),
    handler varchar(64),
    status varchar(16) not null default 'OPEN',
    created_at timestamptz not null default now(),
    constraint ck_fault_status check (status in ('OPEN', 'CLOSED'))
);

create index if not exists idx_fault_device on device_fault (device_id, occurred_at desc);
create index if not exists idx_fault_status on device_fault (status);

insert into sys_role_permission (role_id, permission) values
(1, 'device'),
(3, 'device')
on conflict do nothing;

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
('device.check.day-ahead', '1', 'NUMBER', '提前生成点检任务(天)', '设备',
 '每天定时按设备生成点检任务的提前天数，1 表示提前一天'),
('device.maintain.notice-days', '7', 'NUMBER', '保养到期提前提醒(天)', '设备',
 '保养计划在到期前几天开始提醒')
on conflict (config_key) do nothing;
