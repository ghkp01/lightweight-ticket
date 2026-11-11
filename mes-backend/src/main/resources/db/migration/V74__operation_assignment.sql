-- 工序多人分配：一道工序可以分给多个人做，各自带分配数量（报工数配比）。
--
-- 原来一道工序只能派一个人：流水线上三个人一起做装配，只能记在其中一个人头上，
-- 计件工资就算错了。这里加一张分配子表，报工时按人校验配额。
--
-- 兼容：只派一个人时仍然写 work_order_operation.assigned_user_id / assigned_worker
-- （看板、扫码、报工默认带出都还按这两个字段走），同时在这张表里留一条分配行。

create table if not exists work_order_operation_assignment (
    id bigint generated always as identity primary key,
    operation_id bigint not null references work_order_operation(id) on delete cascade,
    user_id bigint not null references sys_user(id),
    worker_name varchar(64) not null,
    quota_qty numeric(18,4) not null default 0,
    reported_qty numeric(18,4) not null default 0,
    created_at timestamptz not null default now(),
    constraint uk_wo_op_assignment unique (operation_id, user_id)
);

create index if not exists idx_wo_op_assignment_op on work_order_operation_assignment (operation_id);

comment on table work_order_operation_assignment is
    '工序多人分配：每人一行，quota_qty 是分配数量，reported_qty 是按该人累计报工的数量';
