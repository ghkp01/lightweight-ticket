-- 单轨化：BPMN 成为审批流程的唯一事实来源，删掉并行的节点表。
--
-- 背景：以前「画布画的流程」把结构与审批人写进 bpmn_xml，
-- 「老设计器配的流程」把同一份信息写进 sys_approval_node，
-- 两套数据各自维护，谁也没法保证一致 —— 建模器画的流程在节点表里没行，
-- 于是超时提醒、允许转交、允许加签这些开关静默失效。
--
-- 现在：
--   1. 流程结构 + 审批人 -> 全部编译进 BPMN（candidateGroups / candidateUsers / assignee / 多实例集合）；
--   2. BPMN 表达不了的业务策略（超时、转交、加签、抄送人）-> 本表，按 node_key 挂到 BPMN 的用户任务上；
--   3. sys_approval_node 整表删除，节点清单改为从已部署的 BPMN 解析。
create table if not exists sys_approval_node_policy (
    id bigint generated always as identity primary key,
    flow_id bigint not null references sys_approval_flow(id) on delete cascade,
    node_key varchar(64) not null,
    node_name varchar(128),
    timeout_hours int,
    timeout_action varchar(16),
    allow_transfer boolean not null default true,
    allow_add_sign boolean not null default true,
    cc_users varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (flow_id, node_key)
);

comment on table sys_approval_node_policy is
    'BPMN 表达不了的节点业务策略：按 flow_id + node_key 挂到流程里的用户任务';
comment on column sys_approval_node_policy.node_key is '对应 BPMN userTask 的 id';
comment on column sys_approval_node_policy.timeout_hours is '超过多少小时没处理就提醒，空表示不提醒';
comment on column sys_approval_node_policy.timeout_action is 'REMIND 只提醒 / AUTO_PASS 自动通过';
comment on column sys_approval_node_policy.allow_transfer is 'false 表示该节点不允许转交';
comment on column sys_approval_node_policy.allow_add_sign is 'false 表示该节点不允许加签';
comment on column sys_approval_node_policy.cc_users is '抄送人 username，逗号分隔';

create index if not exists idx_node_policy_flow on sys_approval_node_policy (flow_id);

-- 老节点表里的策略搬过来（有 item_key 的行才有意义），然后整表删除
insert into sys_approval_node_policy (flow_id, node_key, node_name, timeout_hours, timeout_action,
                                      allow_transfer, allow_add_sign, cc_users)
select n.flow_id, n.item_key, n.name, n.timeout_hours, n.timeout_action,
       coalesce(n.allow_transfer, true), coalesce(n.allow_add_sign, true),
       nullif(n.cc_users, '')
from sys_approval_node n
where n.item_key is not null
  and (n.timeout_hours is not null or n.timeout_action is not null
       or n.allow_transfer = false or n.allow_add_sign = false or coalesce(n.cc_users, '') <> '')
on conflict (flow_id, node_key) do nothing;

drop table if exists sys_approval_node;
