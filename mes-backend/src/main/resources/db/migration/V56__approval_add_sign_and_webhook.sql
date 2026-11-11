-- 加签：不依赖引擎的多实例改造，而是挂一条「加签待办」在原待办旁边，
-- 加签人处理完原待办才能继续；加签人不同意则整单驳回。
create table if not exists sys_approval_extra_task (
    id bigint generated always as identity primary key,
    instance_id varchar(64) not null,
    task_id varchar(64) not null,
    node_key varchar(64),
    node_name varchar(64),
    assignee varchar(64) not null,
    added_by varchar(64) not null,
    status varchar(16) not null default 'PENDING',
    action varchar(16),
    comment varchar(255),
    created_at timestamptz not null default now(),
    acted_at timestamptz
);

create index if not exists idx_approval_extra_assignee on sys_approval_extra_task (assignee, status);
create index if not exists idx_approval_extra_task on sys_approval_extra_task (task_id, status);

comment on table sys_approval_extra_task is '加签待办：挂在某条审批待办旁边的追加审批人';

-- 自定义逻辑：流程结束 / 被驳回时回调业务方的 HTTP 接口
alter table sys_approval_flow add column if not exists webhook_url varchar(500);
comment on column sys_approval_flow.webhook_url is '流程结束或驳回时 POST 的地址，用于接自定义逻辑';
