-- 低代码自定义字段：字段元数据 + 销售订单扩展值（JSONB）
create table if not exists sys_custom_field (
    id bigserial primary key,
    entity_type varchar(64) not null,
    field_key varchar(64) not null,
    label varchar(64) not null,
    field_type varchar(16) not null,
    required boolean not null default false,
    options jsonb,
    default_value jsonb,
    sort_no int not null default 0,
    enabled boolean not null default true,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ux_sys_custom_field unique (entity_type, field_key),
    constraint ck_sys_custom_field_type
        check (field_type in ('TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'SELECT', 'SWITCH')),
    constraint ck_sys_custom_field_key
        check (field_key ~ '^[a-z][a-z0-9_]{0,40}$')
);

create index if not exists ix_sys_custom_field_entity
    on sys_custom_field (entity_type, sort_no);

comment on table sys_custom_field is '低代码自定义字段定义：实体类型 + 字段键 + 控件类型 + 校验规则';
comment on column sys_custom_field.options is 'SELECT 类型的可选项，JSON 字符串数组';

-- 销售订单扩展字段值：不影响主表结构，字段定义在 sys_custom_field
alter table sales_order add column if not exists ext_data jsonb not null default '{}'::jsonb;
create index if not exists ix_sales_order_ext_data on sales_order using gin (ext_data);
comment on column sales_order.ext_data is '自定义字段值，由 sys_custom_field 定义并校验';

-- 自定义字段设计器权限：只给管理员
insert into sys_role_permission (role_id, permission) values
(1, 'system_custom_field')
on conflict do nothing;
