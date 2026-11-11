-- 多工厂数据范围：工单与销售订单记所属工厂，开关默认关闭
alter table work_order add column if not exists factory_org_id bigint references sys_org(id);
alter table sales_order add column if not exists factory_org_id bigint references sys_org(id);

comment on column work_order.factory_org_id is '所属工厂（组织树 type = PLANT），多工厂隔离时按它过滤';
comment on column sales_order.factory_org_id is '所属工厂（组织树 type = PLANT），多工厂隔离时按它过滤';

-- 历史数据回填到唯一工厂，保证开启隔离后老单据仍然可见
update work_order w
set factory_org_id = (select id from sys_org where type = 'PLANT' order by id limit 1)
where w.factory_org_id is null;

update sales_order s
set factory_org_id = (select id from sys_org where type = 'PLANT' order by id limit 1)
where s.factory_org_id is null;

create index if not exists idx_wo_factory on work_order (factory_org_id);
create index if not exists idx_so_factory on sales_order (factory_org_id);

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
('org.isolation.enabled', 'false', 'BOOLEAN', '启用多工厂数据隔离', '安全',
 '开启后工单与销售订单按所属工厂过滤；单工厂使用请保持关闭')
on conflict (config_key) do nothing;
