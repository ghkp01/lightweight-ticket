-- 自定义字段：按工厂 + 角色控制可见/可编辑
alter table sys_custom_field add column if not exists factory_org_id bigint;
alter table sys_custom_field add column if not exists visible_roles jsonb;
alter table sys_custom_field add column if not exists editable_roles jsonb;

comment on column sys_custom_field.factory_org_id is '作用工厂；null 表示全局字段';
comment on column sys_custom_field.visible_roles is '可见角色编码数组；null/空表示所有角色可见';
comment on column sys_custom_field.editable_roles is '可编辑角色编码数组；null/空表示所有可见角色可编辑';

-- 唯一键改为按作用域：全局一套，每个工厂可以各有一套同名字段
alter table sys_custom_field drop constraint if exists ux_sys_custom_field;

create unique index if not exists ux_sys_custom_field_global
    on sys_custom_field (entity_type, field_key)
    where factory_org_id is null;

create unique index if not exists ux_sys_custom_field_factory
    on sys_custom_field (entity_type, factory_org_id, field_key)
    where factory_org_id is not null;
