-- 通用审批流：流程定义（多级节点）+ 实例 + 任务。
-- 先接一张单据（跨厂调拨），后续按 biz_type 继续加，不动流程引擎。
create table if not exists sys_approval_flow (
    id bigint generated always as identity primary key,
    code varchar(64) not null unique,
    name varchar(128) not null,
    biz_type varchar(32) not null,
    enabled boolean not null default true,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now()
);

create table if not exists sys_approval_node (
    id bigint generated always as identity primary key,
    flow_id bigint not null references sys_approval_flow(id) on delete cascade,
    seq int not null,
    name varchar(64) not null,
    approver_role varchar(32) not null,
    remark varchar(255),
    unique (flow_id, seq)
);

create table if not exists sys_approval_instance (
    id bigint generated always as identity primary key,
    flow_id bigint not null references sys_approval_flow(id),
    flow_code varchar(64) not null,
    biz_type varchar(32) not null,
    biz_id bigint not null,
    biz_no varchar(64),
    title varchar(200) not null,
    applicant varchar(64) not null,
    status varchar(16) not null default 'PENDING',
    current_seq int not null default 1,
    current_node varchar(64),
    remark varchar(255),
    submitted_at timestamptz not null default now(),
    finished_at timestamptz
);

create table if not exists sys_approval_task (
    id bigint generated always as identity primary key,
    instance_id bigint not null references sys_approval_instance(id) on delete cascade,
    seq int not null,
    node_name varchar(64) not null,
    approver_role varchar(32) not null,
    status varchar(16) not null default 'PENDING',
    approver varchar(64),
    action varchar(16),
    comment varchar(255),
    acted_at timestamptz
);

create index if not exists idx_approval_task_status on sys_approval_task (status, approver_role);
create index if not exists idx_approval_instance_biz on sys_approval_instance (biz_type, biz_id);
create index if not exists idx_approval_instance_status on sys_approval_instance (status);

comment on table sys_approval_flow is '审批流定义：按业务类型绑定，一个业务类型只启用一条';
comment on table sys_approval_node is '审批节点：按 seq 顺序，approver_role 决定谁能处理该节点任务';
comment on table sys_approval_instance is '审批实例：一张业务单据一条';
comment on table sys_approval_task is '审批任务：每个节点一条，处理完留下意见与时间';

-- 调拨单：跨厂标记 + 审批实例
alter table transfer_order add column if not exists cross_factory boolean not null default false;
alter table transfer_order add column if not exists approval_instance_id bigint;

comment on column transfer_order.cross_factory is '是否跨厂调拨：源仓与目标仓分属不同工厂时为真，需走审批';
comment on column transfer_order.approval_instance_id is '关联的审批实例';

-- 默认流程：跨厂调拨先计划审、再仓库确认
insert into sys_approval_flow (code, name, biz_type, enabled, remark, created_by)
values ('CROSS_FACTORY_TRANSFER', '跨厂调拨审批', 'TRANSFER', true,
        '源厂计划审核数量与交期，目标厂仓库确认收货仓位', 'system')
on conflict (code) do nothing;

insert into sys_approval_node (flow_id, seq, name, approver_role, remark)
select f.id, 1, '源厂计划审核', 'PLANNER', '核对调出数量、在库可用与交期'
from sys_approval_flow f where f.code = 'CROSS_FACTORY_TRANSFER'
on conflict (flow_id, seq) do nothing;

insert into sys_approval_node (flow_id, seq, name, approver_role, remark)
select f.id, 2, '目标厂仓库确认', 'WAREHOUSE', '确认目标厂可以接收并安排入库'
from sys_approval_flow f where f.code = 'CROSS_FACTORY_TRANSFER'
on conflict (flow_id, seq) do nothing;

insert into sys_role_permission (role_id, permission) values
(1, 'system_approval')
on conflict do nothing;
