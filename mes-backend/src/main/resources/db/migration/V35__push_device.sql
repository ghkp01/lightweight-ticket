-- 移动端推送设备：厂商推送（极光 / 个推）与微信公众号模板消息的注册表。
-- 站内通知仍然落 sys_notice，这里只负责「同一个人的手机怎么收到」。
create table sys_push_device (
    id bigint generated always as identity primary key,
    username varchar(64) not null,
    platform varchar(24) not null,
    provider varchar(24) not null default 'NONE',
    token varchar(255) not null,
    enabled boolean not null default true,
    last_seen_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    constraint ck_push_platform check (platform in ('APP-ANDROID', 'APP-IOS', 'APP-HARMONY', 'WECHAT-MP', 'H5')),
    constraint uk_push_device unique (username, platform, token)
);

create index idx_push_device_user on sys_push_device(username, enabled);

comment on table sys_push_device is '移动端推送设备：厂商推送与公众号的 device/token 登记，默认不启用第三方通道';
comment on column sys_push_device.provider is 'NONE 未接通道 / JPUSH 极光 / GETUI 个推 / WECHAT 微信模板消息';
comment on column sys_push_device.token is '厂商推送 registrationId 或微信 openid';
