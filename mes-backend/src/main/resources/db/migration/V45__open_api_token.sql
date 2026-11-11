-- 对外接口令牌：给 ERP / 财务等外部系统调用开放接口用
create table if not exists sys_api_token (
    id bigint generated always as identity primary key,
    name varchar(64) not null,
    token_hash varchar(64) not null unique,
    token_prefix varchar(16) not null,
    scopes varchar(255) not null default 'READ',
    enabled boolean not null default true,
    expires_at timestamptz,
    last_used_at timestamptz,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    remark varchar(255)
);

create index if not exists idx_api_token_hash on sys_api_token (token_hash);

comment on table sys_api_token is '对外接口令牌：只存 SHA-256 摘要，明文仅创建时返回一次';
comment on column sys_api_token.scopes is '权限范围，逗号分隔：WORK_ORDER / INVENTORY / MATERIAL / COST';

insert into sys_role_permission (role_id, permission) values
(1, 'system_api')
on conflict do nothing;

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
('open.api.enabled', 'true', 'BOOLEAN', '启用对外接口', '接口',
 '关闭后 /api/open/** 一律拒绝，已发出的令牌也立即失效')
on conflict (config_key) do nothing;
