-- 调拨单补一个「计划日期」，给审批里的字段级权限做可编辑字段
alter table transfer_order add column if not exists planned_date date;

-- 灰度发布：同一业务可以在「稳定版本」和「灰度版本」之间按比例分流
alter table sys_approval_flow
    add column if not exists stable_version int,
    add column if not exists canary_version int,
    add column if not exists canary_percent int not null default 0,
    add column if not exists signature_required boolean not null default false;

comment on column sys_approval_flow.stable_version is '稳定版本号，灰度期间新单据默认走它';
comment on column sys_approval_flow.canary_version is '灰度版本号';
comment on column sys_approval_flow.canary_percent is '灰度比例 0-100，0 表示不灰度';
comment on column sys_approval_flow.signature_required is '审批通过时是否必须手写签名';

-- 表单字段级权限：按节点配置每个字段「可见只读 / 可编辑 / 隐藏」
create table if not exists sys_approval_field_rule (
    id bigint generated always as identity primary key,
    flow_id bigint not null,
    node_key varchar(64) not null,
    field_key varchar(64) not null,
    access varchar(8) not null default 'VIEW',
    constraint uk_approval_field_rule unique (flow_id, node_key, field_key)
);

comment on table sys_approval_field_rule is '节点级表单字段权限：VIEW 只读 / EDIT 可改 / HIDDEN 不可见';

-- 手写签名：以 PNG base64 存，随审批记录展示
create table if not exists sys_approval_signature (
    id bigint generated always as identity primary key,
    instance_id varchar(64) not null,
    task_id varchar(64) not null,
    node_key varchar(64),
    approver varchar(64) not null,
    image text not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_approval_signature_instance on sys_approval_signature (instance_id);
