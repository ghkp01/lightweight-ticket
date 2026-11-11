-- 排产计划与车间看板

alter table sales_order add column priority varchar(16) not null default 'NORMAL';
alter table work_order add column priority varchar(16) not null default 'NORMAL';
alter table work_order add column schedule_date date;
alter table work_order add column schedule_seq int;
alter table work_order add column scheduled_start date;
alter table work_order add column scheduled_end date;

create index idx_wo_schedule on work_order(schedule_date, schedule_seq);

insert into sys_role_permission (role_id, permission) values
(1, 'production:schedule'),
(3, 'production:schedule')
on conflict do nothing;
