-- 审批流迁移到 Flowable：配置表加节点树字段，流程表存 BPMN 与部署信息。
-- sys_approval_instance / sys_approval_task 保留为历史（旧实例只读），新单据全部走 Flowable。

alter table sys_approval_node
    add column if not exists item_key varchar(24),
    add column if not exists parent_key varchar(24),
    add column if not exists branch_key varchar(16);

-- 老节点补一个稳定的 key，BPMN 里的 activityId 直接用它
update sys_approval_node set item_key = 'n' || seq where item_key is null;

alter table sys_approval_flow
    add column if not exists bpmn_xml text,
    add column if not exists process_key varchar(64),
    add column if not exists process_version int,
    add column if not exists deployment_id varchar(64);

-- 旧实例表继续当「单号 ↔ Flowable 实例」的索引，页面上的实例 id 仍是我们自己的数字 id
alter table sys_approval_instance add column if not exists process_id varchar(64);
create index if not exists idx_approval_instance_pid on sys_approval_instance (process_id);

comment on column sys_approval_node.item_key is '节点在建图与 BPMN 里的稳定标识，如 n1';
comment on column sys_approval_node.parent_key is '所属容器节点（条件分支 / 并行分支）的 item_key';
comment on column sys_approval_node.branch_key is '在容器里的哪条支路：TRUE / FALSE / B0 / B1 ...';
comment on column sys_approval_flow.bpmn_xml is '由节点树编译出来的 BPMN 2.0 XML';
comment on column sys_approval_flow.process_key is 'Flowable 流程定义 key，与 code 一致';
comment on column sys_approval_flow.process_version is '最近一次部署的流程版本';

create index if not exists idx_approval_node_tree on sys_approval_node (flow_id, parent_key, branch_key);

-- 抄送 / 自动通过 / 超时这类「不产生待办」的事件，单独记一条，供审批时间线展示
create table if not exists sys_approval_log (
    id bigint generated always as identity primary key,
    instance_id varchar(64) not null,
    node_key varchar(24),
    node_name varchar(64),
    action varchar(16) not null,
    comment varchar(255),
    operator varchar(64),
    created_at timestamptz not null default now()
);
create index if not exists idx_approval_log_instance on sys_approval_log (instance_id, created_at);

-- 迁移期：旧引擎上还没走完的实例统一关闭，单据退回草稿，重新提交后走 Flowable
update sys_approval_instance
   set status = 'CANCELLED', finished_at = now()
 where status = 'PENDING';

update sys_approval_task
   set status = 'SKIPPED'
 where status = 'PENDING';

update transfer_order
   set status = 'DRAFT'
 where status = 'PENDING_APPROVAL';
