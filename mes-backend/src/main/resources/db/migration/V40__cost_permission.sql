-- 成本核算权限：管理员与计划可见，成本属于经营数据
insert into sys_role_permission (role_id, permission) values
(1, 'cost'),
(3, 'cost')
on conflict do nothing;
