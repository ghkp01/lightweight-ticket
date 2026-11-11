-- 应付与发票接入多工厂：暂估随入库仓库，发票随采购单收货仓库，红字发票继承蓝票。
alter table ap_accrual add column if not exists factory_org_id bigint references sys_org(id);
alter table ap_invoice add column if not exists factory_org_id bigint references sys_org(id);

comment on column ap_accrual.factory_org_id is '所属工厂：取生成暂估的入库单所在仓库的工厂';
comment on column ap_invoice.factory_org_id is '所属工厂：取采购单收货仓库，红字发票继承蓝票';

-- 历史数据回填：暂估沿入库仓库，发票沿采购单仓库，兜底到唯一工厂
update ap_accrual a
set factory_org_id = (select w.factory_org_id
                      from inbound_order io join warehouse w on w.id = io.warehouse_id
                      where io.id = a.inbound_order_id)
where a.factory_org_id is null;

update ap_invoice i
set factory_org_id = coalesce(
        (select w.factory_org_id
         from purchase_order o join warehouse w on w.id = o.warehouse_id
         where o.id = i.purchase_order_id),
        (select c.factory_org_id from ap_invoice c where c.id = i.related_invoice_id),
        (select id from sys_org where type = 'PLANT' order by id limit 1))
where i.factory_org_id is null;

create index if not exists idx_accrual_factory on ap_accrual (factory_org_id);
create index if not exists idx_invoice_factory on ap_invoice (factory_org_id);
