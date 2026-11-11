-- 按钮级操作权限：权限码与角色授权

insert into sys_role_permission (role_id, permission) values
(1, 'sales:create'), (1, 'sales:update'), (1, 'sales:confirm'), (1, 'sales:cancel'),
(1, 'sales:delete'), (1, 'sales:to_production'),
(1, 'production:create'), (1, 'production:release'), (1, 'production:issue'),
(1, 'production:report'), (1, 'production:assign'), (1, 'production:return'),
(1, 'production:close'),
(1, 'shipping:create'), (1, 'shipping:ship'), (1, 'shipping:sign'), (1, 'shipping:delete'),
(1, 'quality:create'),
(1, 'returns:create'), (1, 'returns:process'), (1, 'returns:close'),
(1, 'warehouse:inbound'), (1, 'warehouse:outbound'), (1, 'warehouse:transfer'),
(1, 'warehouse:stocktake'),
(1, 'system:user'), (1, 'system:role'), (1, 'system:code')
on conflict do nothing;

insert into sys_role_permission (role_id, permission) values
(2, 'sales:create'), (2, 'sales:update'), (2, 'sales:confirm'), (2, 'sales:cancel'),
(2, 'sales:delete'), (2, 'sales:to_production'),
(2, 'shipping:create'), (2, 'shipping:sign'),
(2, 'returns:create'), (2, 'returns:close')
on conflict do nothing;

insert into sys_role_permission (role_id, permission) values
(3, 'sales:to_production'),
(3, 'production:create'), (3, 'production:release'), (3, 'production:issue'),
(3, 'production:report'), (3, 'production:assign'), (3, 'production:return'),
(3, 'production:close')
on conflict do nothing;

insert into sys_role_permission (role_id, permission) values
(4, 'production:issue'), (4, 'production:report'), (4, 'production:return'),
(4, 'shipping:create'), (4, 'shipping:ship'), (4, 'shipping:sign'), (4, 'shipping:delete'),
(4, 'warehouse:inbound'), (4, 'warehouse:outbound'), (4, 'warehouse:transfer'),
(4, 'warehouse:stocktake'),
(4, 'returns:process')
on conflict do nothing;

insert into sys_role_permission (role_id, permission) values
(5, 'production:report'), (5, 'quality:create'),
(5, 'returns:create'), (5, 'returns:process'), (5, 'returns:close')
on conflict do nothing;
