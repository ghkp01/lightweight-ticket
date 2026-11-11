-- 首次登录强制改密：默认口令不能再当长期口令用。
-- 以前 admin / 123456 建完就一直能用，谁拿到默认口令谁就能进。
alter table sys_user add column if not exists must_change_password boolean not null default false;

comment on column sys_user.must_change_password is
    'true 时登录后只能调改密接口，改完才放行其它业务接口；管理员重置密码或新建用户时置为 true';

-- 存量账号：从来没改过密码的（也就是还在用初始口令）一律要求改
update sys_user
set must_change_password = true
where password_updated_at is null;
