-- 系统配置：先落水印开关，后续配置项继续往这张表加

create table sys_config (
    config_key varchar(64) primary key,
    config_value text not null default '',
    value_type varchar(16) not null default 'TEXT',
    title varchar(64) not null,
    group_name varchar(32) not null default '通用',
    remark varchar(255),
    updated_by varchar(64),
    updated_at timestamptz not null default now(),
    constraint ck_config_type check (value_type in ('TEXT', 'BOOLEAN', 'NUMBER'))
);
comment on table sys_config is '系统参数：按 key 存，value_type 决定前端控件与后端校验';

insert into sys_config (config_key, config_value, value_type, title, group_name, remark)
values ('security.watermark.enabled', 'true', 'BOOLEAN', '页面水印', '安全',
        '关闭后所有页面不再显示水印；重新打开立即生效，无需重启')
on conflict (config_key) do nothing;

-- 权限：系统配置菜单与保存操作
insert into sys_role_permission (role_id, permission) values
(1, 'system_config'), (1, 'config:save')
on conflict do nothing;
