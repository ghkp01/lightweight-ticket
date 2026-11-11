-- 派工选人 + 返修从中间工序起做

-- 1. 派工指向系统用户：assigned_worker 保留为显示名，assigned_user_id 才是责任主体
alter table work_order_operation add column assigned_user_id bigint references sys_user(id);
create index idx_wo_operation_user on work_order_operation(assigned_user_id);
comment on column work_order_operation.assigned_worker is '派工显示名（取用户姓名），计件与看板展示用';
comment on column work_order_operation.assigned_user_id is '派工责任人（sys_user.id），按人统计与消息推送用';

-- 2. 历史数据：按姓名或账号回填责任人，匹配不上的留空，由计划重新派工
update work_order_operation o
set assigned_user_id = u.id
from sys_user u
where o.assigned_user_id is null
  and o.assigned_worker is not null
  and o.assigned_worker <> ''
  and (u.real_name = o.assigned_worker or u.username = o.assigned_worker);

-- 3. 返修起点：记录从第几道工序开始返修，空表示从前道开始（整件返修）
alter table work_order add column rework_from_seq int;
comment on column work_order.rework_from_seq is '返修起点工序序号，空表示从第一道工序开始';
