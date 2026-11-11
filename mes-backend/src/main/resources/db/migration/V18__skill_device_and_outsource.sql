-- 外协产能口径、人员技能、设备台账

-- 1. 人员技能：谁能做哪道工序。只有被配置过的工序才做持证校验，避免一上来把所有工序都锁死
create table user_skill (
    user_id bigint not null references sys_user(id) on delete cascade,
    process_id bigint not null references process(id) on delete cascade,
    level varchar(16) not null default 'NORMAL',
    remark varchar(128),
    created_at timestamptz not null default now(),
    primary key (user_id, process_id),
    constraint ck_user_skill_level check (level in ('JUNIOR', 'NORMAL', 'SENIOR'))
);
create index idx_user_skill_process on user_skill(process_id);
comment on table user_skill is '人员工序技能：派工候选人过滤与持证校验';

-- 2. 设备台账：工作中心内部的产能单元，停机或维修时不计入可用产能
create table work_center_device (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(64) not null,
    work_center_id bigint not null references work_center(id) on delete cascade,
    capacity_minutes integer not null default 480,
    status varchar(16) not null default 'RUNNING',
    enabled boolean not null default true,
    remark varchar(128),
    created_at timestamptz not null default now(),
    constraint ck_device_status check (status in ('RUNNING', 'IDLE', 'MAINTENANCE', 'OFFLINE')),
    constraint ck_device_capacity check (capacity_minutes between 0 and 1440)
);
create index idx_device_center on work_center_device(work_center_id);
comment on table work_center_device is '工作中心内的设备台账，RUNNING/IDLE 计入可用产能';

-- 3. 权限：设备台账维护
insert into sys_role_permission (role_id, permission) values
(1, 'device:save'), (3, 'device:save')
on conflict do nothing;

-- 4. 外协中心：外协不占厂内日产能，仅作为执行地点与成本归口
insert into work_center (code, name, org_id, type, daily_capacity_minutes, enabled, remark)
select 'WC-OUT', '外协中心', o.id, 'OUTSOURCE', 480, true, '外协加工，不占用厂内日产能'
from sys_org o
where o.name = '一号车间'
  and not exists (select 1 from work_center w where w.code = 'WC-OUT');

-- 5. 演示技能：下料、装配、终检三个工序启用持证校验，其余工序不限制
insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR'
from sys_user u, process p
where u.username = 'worker1' and p.code = 'CUT'
on conflict do nothing;

insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR'
from sys_user u, process p
where u.username = 'worker2' and p.code in ('ASSY', 'PACK')
on conflict do nothing;

insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR'
from sys_user u, process p
where u.username = 'qc' and p.code = 'QC'
on conflict do nothing;

-- 6. 演示设备：可用设备产能之和不低于中心日产能，保证排产口径不变
insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-CUT-D1', '锯切机 01', w.id, 480, 'RUNNING', '下料主力设备'
from work_center w where w.code = 'WC-CUT'
  and not exists (select 1 from work_center_device d where d.code = 'WC-CUT-D1');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-MACH-D1', 'CNC-01', w.id, 240, 'RUNNING', null
from work_center w where w.code = 'WC-MACH'
  and not exists (select 1 from work_center_device d where d.code = 'WC-MACH-D1');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-MACH-D2', 'CNC-02', w.id, 240, 'RUNNING', null
from work_center w where w.code = 'WC-MACH'
  and not exists (select 1 from work_center_device d where d.code = 'WC-MACH-D2');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-ASSY-D1', '装配工位 1', w.id, 240, 'RUNNING', null
from work_center w where w.code = 'WC-ASSY'
  and not exists (select 1 from work_center_device d where d.code = 'WC-ASSY-D1');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-ASSY-D2', '装配工位 2', w.id, 240, 'RUNNING', null
from work_center w where w.code = 'WC-ASSY'
  and not exists (select 1 from work_center_device d where d.code = 'WC-ASSY-D2');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-SPRAY-D1', '喷房 01', w.id, 360, 'RUNNING', null
from work_center w where w.code = 'WC-SPRAY'
  and not exists (select 1 from work_center_device d where d.code = 'WC-SPRAY-D1');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-QC-D1', '检测台 01', w.id, 240, 'RUNNING', null
from work_center w where w.code = 'WC-QC'
  and not exists (select 1 from work_center_device d where d.code = 'WC-QC-D1');

insert into work_center_device (code, name, work_center_id, capacity_minutes, status, remark)
select 'WC-PACK-D1', '包装线 01', w.id, 480, 'RUNNING', null
from work_center w where w.code = 'WC-PACK'
  and not exists (select 1 from work_center_device d where d.code = 'WC-PACK-D1');
