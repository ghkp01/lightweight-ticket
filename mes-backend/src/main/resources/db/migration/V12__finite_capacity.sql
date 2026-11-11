-- 有限产能排产：工序级日产能与工序排程时间

alter table process add column daily_capacity_minutes int not null default 480;
update process set daily_capacity_minutes = 360 where code = 'MACH';
update process set daily_capacity_minutes = 240 where code = 'QC';

alter table work_order_operation add column schedule_date date;
alter table work_order_operation add column scheduled_start timestamptz;
alter table work_order_operation add column scheduled_end timestamptz;

create index idx_op_schedule on work_order_operation(schedule_date);
create index idx_op_process on work_order_operation(process_id);
