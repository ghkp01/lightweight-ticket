-- 物料缺口逾期预警要按供应商落到具体采购员头上，而不是一锅端推给计划角色。
-- supplier.buyer 存 sys_user.username，留空表示没指定采购员，预警时回退到 PLANNER。

alter table supplier add column if not exists buyer varchar(64);

comment on column supplier.buyer is '负责该供应商的采购员用户名（sys_user.username）；为空则预警回退给计划角色';

create index if not exists idx_supplier_buyer on supplier (buyer);
