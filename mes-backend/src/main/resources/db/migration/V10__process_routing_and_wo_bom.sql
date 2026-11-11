-- 工序主数据、产品工艺路线、BOM 工序归属、工单级 BOM（非标调整）

-- 1. 工序主数据
create table process (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(64) not null unique,
    category varchar(24) not null default 'MACHINING',
    default_minutes numeric(10,2) not null default 0,
    default_price numeric(12,2) not null default 0,
    need_inspection boolean not null default false,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_process_category check (category in
        ('CUTTING','MACHINING','BENDING','ASSEMBLY','SPRAYING','INSPECTION','PACKING','OUTSOURCE'))
);

insert into process (code, name, category, default_minutes, default_price, need_inspection, remark) values
('CUT',   '下料',   'CUTTING',    1.50, 1.20, false, '锯切 / 激光下料'),
('MACH',  '机加工', 'MACHINING',  3.00, 2.50, false, 'CNC 铣削、钻孔、攻丝'),
('BEND',  '折弯',   'BENDING',    2.20, 2.20, false, '数控折弯'),
('ASSY',  '装配',   'ASSEMBLY',   2.00, 2.00, false, '总装与紧固'),
('SPRAY', '喷涂',   'SPRAYING',   1.60, 1.50, false, '喷粉 / 喷漆'),
('QC',    '终检',   'INSPECTION', 0.50, 0.00, true,  '尺寸与外观全检'),
('PACK',  '包装',   'PACKING',    0.80, 0.50, false, '贴标、装箱');

-- 2. 产品工艺路线
create table product_routing (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id) on delete cascade,
    seq int not null,
    process_id bigint not null references process(id),
    minutes_per_piece numeric(10,2) not null default 0,
    unit_price numeric(12,2) not null default 0,
    need_inspection boolean not null default false,
    remark varchar(255),
    constraint uk_routing unique (product_id, seq)
);

-- 成品：铝合金外壳 / 不锈钢支架；半成品：外壳半成品 / 支架半成品
insert into product_routing (product_id, seq, process_id, minutes_per_piece, unit_price, need_inspection)
select 1, 1, id, default_minutes, default_price, need_inspection from process where code = 'CUT'
union all select 1, 2, id, default_minutes, default_price, need_inspection from process where code = 'MACH'
union all select 1, 3, id, default_minutes, default_price, need_inspection from process where code = 'ASSY'
union all select 2, 1, id, default_minutes, default_price, need_inspection from process where code = 'CUT'
union all select 2, 2, id, default_minutes, default_price, need_inspection from process where code = 'BEND'
union all select 2, 3, id, default_minutes, default_price, need_inspection from process where code = 'ASSY'
union all select 7, 1, id, default_minutes, default_price, need_inspection from process where code = 'CUT'
union all select 7, 2, id, default_minutes, default_price, need_inspection from process where code = 'SPRAY'
union all select 8, 1, id, default_minutes, default_price, need_inspection from process where code = 'CUT'
union all select 8, 2, id, default_minutes, default_price, need_inspection from process where code = 'BEND';

-- 3. BOM 行关联工序、替代组、关键件
alter table bom_item add column operation_seq int;
alter table bom_item add column substitute_group varchar(32);
alter table bom_item add column is_key boolean not null default false;
alter table bom_item add column remark varchar(255);

update bom_item set operation_seq = 1;
update bom_item set operation_seq = 3 where product_id in (1, 2) and material_id in (5, 6);
update bom_item set operation_seq = 2 where product_id in (7, 8) and material_id = 5;
update bom_item set is_key = true where material_id in (3, 4);
update bom_item set substitute_group = 'SEAL' where material_id = 6;
update bom_item set remark = '按图号下料，留 5mm 余量' where material_id in (3, 4);

-- 4. 工单工序关联工序主数据
alter table work_order_operation add column process_id bigint references process(id);
alter table work_order_operation add column need_inspection boolean not null default false;

update work_order_operation o
set process_id = p.id, need_inspection = p.need_inspection
from process p
where p.name = o.name;

-- 5. 工单级 BOM（标准 BOM 快照 + 非标调整）
create table work_order_bom (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id) on delete cascade,
    material_id bigint not null references product(id),
    plan_qty numeric(18,4) not null,
    issued_qty numeric(18,4) not null default 0,
    operation_seq int,
    substitute_group varchar(32),
    source varchar(16) not null default 'STANDARD',
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint uk_wo_bom unique (work_order_id, material_id),
    constraint ck_wo_bom_source check (source in ('STANDARD','CUSTOM'))
);

create index idx_wo_bom_wo on work_order_bom(work_order_id);
create index idx_wo_bom_seq on work_order_bom(work_order_id, operation_seq);

-- 6. 工单 BOM 变更日志
create table work_order_bom_change (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id) on delete cascade,
    action varchar(16) not null,
    material_id bigint,
    material_name varchar(128),
    old_qty numeric(18,4),
    new_qty numeric(18,4),
    reason varchar(255),
    changed_by varchar(64),
    changed_at timestamptz not null default now()
);

create index idx_wo_bom_change_wo on work_order_bom_change(work_order_id, changed_at desc);

-- 7. 已有工单按标准 BOM 生成快照（含损耗率）
insert into work_order_bom (work_order_id, material_id, plan_qty, operation_seq, substitute_group, source)
select w.id, b.material_id,
       round(b.qty * w.plan_qty * (1 + coalesce(p.loss_rate, 0)), 4),
       b.operation_seq, b.substitute_group, 'STANDARD'
from work_order w
join product p on p.id = w.product_id
join bom_item b on b.product_id = w.product_id
on conflict (work_order_id, material_id) do nothing;

-- 8. 已领数量回填
update work_order_bom wb
set issued_qty = coalesce((
    select -sum(t.qty)
    from stock_txn t
    join work_order w on w.work_no = t.ref_no
    where t.txn_type = 'ISSUE' and w.id = wb.work_order_id and t.product_id = wb.material_id
), 0);

-- 9. 工艺与工单 BOM 的操作权限
insert into sys_role_permission (role_id, permission) values
(1, 'process:save'), (1, 'routing:save'), (1, 'bom:save'), (1, 'production:bom'),
(3, 'routing:save'), (3, 'production:bom')
on conflict do nothing;
