-- 登录安全：失败锁定、密码策略、刷新令牌
alter table sys_user add column if not exists failed_attempts int not null default 0;
alter table sys_user add column if not exists locked_until timestamptz;
alter table sys_user add column if not exists last_login_at timestamptz;
alter table sys_user add column if not exists password_updated_at timestamptz;

comment on column sys_user.failed_attempts is '连续登录失败次数，登录成功后清零';
comment on column sys_user.locked_until is '锁定到期时间，空表示未锁定';

create table if not exists sys_refresh_token (
    id bigint generated always as identity primary key,
    username varchar(64) not null,
    token_hash varchar(64) not null unique,
    issued_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked boolean not null default false,
    revoked_at timestamptz,
    client varchar(255)
);

create index if not exists idx_refresh_token_user on sys_refresh_token (username, revoked);
create index if not exists idx_refresh_token_expire on sys_refresh_token (expires_at);

comment on table sys_refresh_token is '刷新令牌：只存 SHA-256 摘要，可撤销，登录一次发一张';

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
('security.login.max-fail', '5', 'NUMBER', '登录失败上限', '安全',
 '连续失败达到该次数后锁定账号；填 0 表示不锁定'),
('security.login.lock-minutes', '15', 'NUMBER', '锁定时长(分钟)', '安全',
 '账号被锁定后多久自动解锁'),
('security.password.min-length', '8', 'NUMBER', '密码最小长度', '安全',
 '改密与新建用户时的最小长度，建议不少于 8 位'),
('security.password.require-complex', 'true', 'BOOLEAN', '密码复杂度要求', '安全',
 '开启后要求大写 / 小写 / 数字 / 符号四类字符里至少三类'),
('security.refresh.days', '7', 'NUMBER', '刷新令牌有效期(天)', '安全',
 '刷新令牌多久失效，过期需重新登录')
on conflict (config_key) do nothing;
