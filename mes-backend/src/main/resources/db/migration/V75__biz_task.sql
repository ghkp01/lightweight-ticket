-- 业务任务：把「谁该做什么」从口头交接落成一条可指派、可追踪、会通知的记录。
--
-- 之前每个环节都只是改单据状态，没人被点名负责：订单转生产后没人算料，
-- 工单下达后没人派工，完工检合格后没人通知销售发货。这张表补的就是这个洞。

create table if not exists biz_task (
    id bigint generated always as identity primary key,
    -- 任务类型：MRP_RUN 算料 / WO_ASSIGN 派工 / WO_PICK 领料 / SHIP_READY 待发货
    task_type varchar(32) not null,
    title varchar(200) not null,
    content varchar(1000),
    -- 关联单据：SALES_ORDER / WORK_ORDER，用于跳转与去重
    ref_type varchar(32) not null,
    ref_id bigint,
    ref_no varchar(64) not null,
    -- 指派去向：角色是兜底池（没人认领时该角色全体可见），指到人后以人为准
    assignee_role varchar(32) not null,
    assignee_username varchar(64),
    assignee_user_id bigint references sys_user(id) on delete set null,
    assignee_name varchar(64),
    priority varchar(16) not null default 'NORMAL',
    status varchar(16) not null default 'OPEN',
    due_date date,
    done_at timestamptz,
    done_by varchar(64),
    done_note varchar(500),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_biz_task_status check (status in ('OPEN', 'DONE', 'CANCELLED')),
    constraint ck_biz_task_priority check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT'))
);

-- 同一张单据的同一类任务只留一条未完成：重复触发不会刷屏，也不会派生重复待办
create unique index if not exists uk_biz_task_open on biz_task (task_type, ref_no) where status = 'OPEN';
create index if not exists idx_biz_task_assignee on biz_task (assignee_username, status);
create index if not exists idx_biz_task_role on biz_task (assignee_role, status);
create index if not exists idx_biz_task_created on biz_task (created_at desc);

create or replace view rm_biz_task as select * from biz_task;

-- 改派是管理动作：只有管理员与计划能把角色池里的任务指到具体人
insert into sys_role_permission (role_id, permission) values
(1, 'task:assign'), (3, 'task:assign')
on conflict do nothing;
