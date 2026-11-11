-- 基础数据导入导出权限：只给管理员
insert into sys_role_permission (role_id, permission) values
(1, 'system_data')
on conflict do nothing;
