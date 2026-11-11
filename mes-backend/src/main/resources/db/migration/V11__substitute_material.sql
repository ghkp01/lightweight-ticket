-- 替代料：主料不足时自动用同组替代料补足

alter table bom_item add column substitute_material_id bigint references product(id);
alter table work_order_bom add column substitute_for bigint references product(id);

alter table work_order_bom drop constraint ck_wo_bom_source;
alter table work_order_bom add constraint ck_wo_bom_source
    check (source in ('STANDARD', 'CUSTOM', 'SUBSTITUTE'));

-- 备用密封圈（B 供应商），作为密封圈的替代料
insert into product (code, name, spec, category, unit, price, cost, safety_stock, loss_rate)
values ('P009', '密封圈（备用）', '硅胶 φ60 · B 供应商', 'RAW', '个', 2.4000, 1.3000, 800, 0)
on conflict (code) do nothing;

insert into inventory (product_id, warehouse_id, qty, locked_qty)
select p.id, 1, 2000, 0 from product p where p.code = 'P009'
on conflict (product_id, warehouse_id) do nothing;

-- 成品 BOM 的密封圈行指定替代料
update bom_item
set substitute_material_id = (select id from product where code = 'P009')
where material_id = (select id from product where code = 'P006');

-- 已有工单补替代料行（需求为 0，仅用于记录实际发出的替代料数量）
insert into work_order_bom (work_order_id, material_id, plan_qty, issued_qty,
                            operation_seq, substitute_for, source)
select wb.work_order_id, b.substitute_material_id, 0, 0,
       wb.operation_seq, wb.material_id, 'SUBSTITUTE'
from work_order_bom wb
join work_order w on w.id = wb.work_order_id
join bom_item b on b.product_id = w.product_id and b.material_id = wb.material_id
where b.substitute_material_id is not null
on conflict (work_order_id, material_id) do nothing;

create index idx_bom_substitute on bom_item(substitute_material_id);
