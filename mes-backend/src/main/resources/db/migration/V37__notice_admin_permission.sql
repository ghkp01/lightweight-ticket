-- 站内通知管理页权限：只给管理员，避免普通用户看到别人的消息
insert into sys_role_permission (role_id, permission) values
(1, 'system_notice')
on conflict do nothing;
