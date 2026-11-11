-- 灰度发布从「只按比例随机」扩成「定向 + 比例」：
-- 定向名单里的人一定走灰度版本（按人 / 按工厂），名单外的人再按比例抽，
-- 这样就能先把新流程放给一个厂或几个关键用户试，而不是全靠运气分流。
alter table sys_approval_flow
    add column if not exists canary_factory_ids varchar(255),
    add column if not exists canary_users varchar(500),
    add column if not exists canary_note varchar(255),
    add column if not exists canary_updated_at timestamptz;

comment on column sys_approval_flow.canary_factory_ids is
    '定向工厂：org id 逗号分隔，发起人所属工厂命中时直接走灰度版本';
comment on column sys_approval_flow.canary_users is
    '定向用户：username 逗号分隔，发起人命中名单时直接走灰度版本';
comment on column sys_approval_flow.canary_note is
    '灰度备注，写清这次要验证什么，方便回看';
comment on column sys_approval_flow.canary_percent is
    '非定向发起人按此比例随机走灰度版本；0 表示只走定向名单';
