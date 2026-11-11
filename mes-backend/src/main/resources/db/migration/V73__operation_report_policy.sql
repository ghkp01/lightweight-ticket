-- 工序报工权限：谁可以给这道工序报工。
--
-- 之前只有「派工人员」，但它只是默认值 —— 任何有 production:report 的人都能报任何工序。
-- 装配线按人计件、关键工序要持证时，这就不够了：得能限定「只有派的那个人能报」。
--   ANY      所有人可报（默认，保持旧行为）
--   ASSIGNED 只有派工人员本人可报

alter table work_order_operation
    add column if not exists report_policy varchar(16) not null default 'ANY';

alter table work_order_operation drop constraint if exists ck_wo_op_report_policy;
alter table work_order_operation
    add constraint ck_wo_op_report_policy check (report_policy in ('ANY', 'ASSIGNED'));

create index if not exists idx_wo_op_report_policy on work_order_operation (report_policy)
    where report_policy = 'ASSIGNED';

-- 主表加列后读模型视图要重建才带上新列（新列只能追加在末尾）
create or replace view rm_work_order_operation as select * from work_order_operation;
