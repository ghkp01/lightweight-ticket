-- 多工厂隔离覆盖全链路：仓库归属工厂，库存 / 采购 / 出入库沿仓库归属，采购申请单独记工厂。
-- 只加仓库与采购申请两列：采购单、入库单、出库单、调拨、盘点都带 warehouse_id，
-- 发货单带 sales_order_id，按关联单据的工厂过滤即可，不用在每张流水表上重复存一份。
alter table warehouse add column if not exists factory_org_id bigint references sys_org(id);
alter table purchase_requisition add column if not exists factory_org_id bigint references sys_org(id);

comment on column warehouse.factory_org_id is '所属工厂（组织树 type = PLANT），库存与采购按它隔离';
comment on column purchase_requisition.factory_org_id is '所属工厂，来源工单有工厂时随工单';

-- 历史数据回填到唯一工厂，开启隔离后老单据与老仓库仍然可见
update warehouse
set factory_org_id = (select id from sys_org where type = 'PLANT' order by id limit 1)
where factory_org_id is null;

update purchase_requisition r
set factory_org_id = coalesce(
        (select w.factory_org_id from work_order w where w.id = r.work_order_id),
        (select id from sys_org where type = 'PLANT' order by id limit 1))
where r.factory_org_id is null;

create index if not exists idx_warehouse_factory on warehouse (factory_org_id);
create index if not exists idx_req_factory on purchase_requisition (factory_org_id);

update sys_config
set remark = '开启后工单、销售、库存与采购按所属工厂过滤；单工厂使用请保持关闭'
where config_key = 'org.isolation.enabled';
