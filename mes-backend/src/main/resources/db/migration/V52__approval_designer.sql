-- 审批流可视化配置：节点支持「角色 / 指定用户」、「或签 / 会签」、条件与抄送
alter table sys_approval_node
    add column if not exists approver_type varchar(8) not null default 'ROLE',
    add column if not exists approver_values varchar(500),
    add column if not exists multi_mode varchar(8) not null default 'ANY',
    add column if not exists condition_field varchar(16),
    add column if not exists condition_op varchar(4),
    add column if not exists condition_value varchar(64),
    add column if not exists cc_users varchar(500);

comment on column sys_approval_node.approver_type is 'ROLE 按角色 / USER 指定用户';
comment on column sys_approval_node.approver_values is 'ROLE 时是角色码，USER 时是用户名，多个用逗号分隔';
comment on column sys_approval_node.multi_mode is 'ANY 或签（任一人通过即可）/ ALL 会签（所有人都通过）';
comment on column sys_approval_node.condition_field is '条件字段：AMOUNT 金额 / QTY 数量 / APPLICANT_ROLE 发起人角色 / BIZ_TYPE 业务类型；空表示无条件';
comment on column sys_approval_node.cc_users is '抄送人用户名，多个用逗号分隔，审批结束时推送';

-- 老节点按角色回填到新字段，行为保持不变
update sys_approval_node
set approver_type = 'ROLE',
    approver_values = coalesce(approver_values, approver_role)
where approver_values is null;

alter table sys_approval_node alter column approver_role drop not null;

alter table sys_approval_task
    add column if not exists assignee varchar(64),
    add column if not exists multi_mode varchar(8);

comment on column sys_approval_task.assignee is '指定审批人用户名；为空表示按角色领取';
comment on column sys_approval_task.multi_mode is '该任务所在节点是或签还是会签';

alter table sys_approval_task alter column approver_role drop not null;

-- 条件上下文随实例保存，供后续节点复用同一份金额/角色判断
alter table sys_approval_instance add column if not exists context_json varchar(500);

create index if not exists idx_approval_task_assignee on sys_approval_task (assignee, status);
