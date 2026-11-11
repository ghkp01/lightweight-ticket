-- 审批节点能力扩展：节点类型（审批 / 抄送 / 自动通过 / 条件）、条件分支分组、
-- 审批人来源（角色 / 指定人 / 发起人 / 各级主管）、节点策略与超时。
alter table sys_approval_node
    add column if not exists node_kind varchar(16) not null default 'APPROVER',
    add column if not exists group_key varchar(32),
    add column if not exists level_up int not null default 1,
    add column if not exists empty_strategy varchar(16) not null default 'AUTO_PASS',
    add column if not exists dedupe_same_user boolean not null default true,
    add column if not exists allow_transfer boolean not null default true,
    add column if not exists allow_add_sign boolean not null default true,
    add column if not exists timeout_hours int,
    add column if not exists timeout_action varchar(16);

-- approver_type 要放下 ORG_LEADER / INITIATOR 这类值
alter table sys_approval_node alter column approver_type type varchar(16);

-- 待办需要知道「什么时候到我手上的」，超时才算得出来
alter table sys_approval_task add column if not exists created_at timestamptz not null default now();
alter table sys_approval_task add column if not exists reminded_at timestamptz;

create index if not exists idx_approval_node_group on sys_approval_node (flow_id, group_key);
create index if not exists idx_approval_task_pending on sys_approval_task (status, created_at);

comment on column sys_approval_node.node_kind is '节点类型：APPROVER 审批 / CC 抄送 / AUTO 自动通过 / CONDITION 条件分支';
comment on column sys_approval_node.group_key is '所属条件节点的 seq；条件不满足时整组跳过';
comment on column sys_approval_node.approver_type is '审批人来源：ROLE 角色 / USER 指定人 / INITIATOR 发起人 / ORG_LEADER 主管';
comment on column sys_approval_node.level_up is 'ORG_LEADER 时向上找第几级组织的负责人，1 表示发起人所在组织';
comment on column sys_approval_node.empty_strategy is '审批人为空时：AUTO_PASS 自动通过 / ADMIN 转管理员 / BLOCK 阻断';
comment on column sys_approval_node.dedupe_same_user is '同一审批人在前面节点已处理过时，本节点自动跳过';
comment on column sys_approval_node.timeout_hours is '超时小时数，空表示不限时';
comment on column sys_approval_node.timeout_action is '超时动作：REMIND 提醒 / AUTO_PASS 自动通过';

-- 已有节点补默认值，保持「两级串行 + 或签」的原行为
update sys_approval_node set node_kind = 'APPROVER' where node_kind is null;
