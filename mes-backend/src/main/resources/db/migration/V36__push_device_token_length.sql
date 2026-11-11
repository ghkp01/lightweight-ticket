-- 浏览器后台推送（Web Push）的订阅信息是整段 JSON（endpoint + keys），
-- 比厂商推送的 registrationId 长，这里把 token 放宽到 1024。
alter table sys_push_device alter column token type varchar(1024);

comment on column sys_push_device.token is '厂商推送 registrationId / 微信 openid / Web Push 订阅 JSON';
