-- 采购与供应商：供应商档案、采购订单，入库单回链采购单

-- 1. 供应商
create table supplier (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(128) not null,
    category varchar(16) not null default 'MATERIAL',
    contact varchar(64),
    phone varchar(32),
    email varchar(128),
    address varchar(255),
    tax_no varchar(32),
    payment_term varchar(64),
    lead_days int not null default 7,
    rating varchar(8) not null default 'B',
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_supplier_category check (category in ('MATERIAL', 'OUTSOURCE', 'SERVICE')),
    constraint ck_supplier_rating check (rating in ('A', 'B', 'C')),
    constraint ck_supplier_lead check (lead_days >= 0)
);
comment on table supplier is '供应商档案：物料 / 外协 / 服务三类，带账期、交期与评级';
comment on column supplier.lead_days is '默认交期天数，建采购单时用来推算预计到货日';

-- 2. 采购订单
create table purchase_order (
    id bigint generated always as identity primary key,
    order_no varchar(32) not null unique,
    supplier_id bigint not null references supplier(id),
    warehouse_id bigint not null references warehouse(id),
    status varchar(16) not null default 'DRAFT',
    order_date date not null default current_date,
    expected_date date,
    need_inspection boolean not null default true,
    total_amount numeric(18,2) not null default 0,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    confirmed_by varchar(64),
    confirmed_at timestamptz,
    constraint ck_po_status check (status in ('DRAFT', 'CONFIRMED', 'PARTIAL', 'RECEIVED', 'CANCELLED'))
);
comment on table purchase_order is '采购订单：确认后才能收货，收货生成入库单，入库完成回写实收数量';
comment on column purchase_order.status is 'DRAFT 草稿 / CONFIRMED 已确认 / PARTIAL 部分到货 / RECEIVED 全部到货 / CANCELLED 已取消';

create table purchase_order_item (
    id bigint generated always as identity primary key,
    order_id bigint not null references purchase_order(id) on delete cascade,
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    received_qty numeric(18,4) not null default 0,
    price numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    remark varchar(255),
    constraint ck_po_item_qty check (qty > 0),
    constraint ck_po_item_received check (received_qty >= 0)
);
create index idx_po_item_order on purchase_order_item (order_id);
create index idx_po_supplier on purchase_order (supplier_id, status);

-- 3. 入库单回链采购单：收货时自动带出供应商、批次与采购明细
alter table inbound_order add column if not exists supplier_id bigint references supplier(id);
alter table inbound_order add column if not exists purchase_order_id bigint references purchase_order(id);
alter table inbound_order_item add column if not exists purchase_item_id bigint references purchase_order_item(id);
create index idx_inbound_purchase on inbound_order (purchase_order_id);
create index idx_inbound_item_purchase on inbound_order_item (purchase_item_id);

-- 4. 演示供应商
insert into supplier (code, name, category, contact, phone, email, address, tax_no, payment_term, lead_days, rating, remark)
values
('S001', '宁波金属材料有限公司', 'MATERIAL', '陈志强', '13700003333', 'sales@nb-metal.local',
 '宁波市北仑区大港工业城 6 号', '91330206MA2AB1234X', '月结 30 天', 7, 'A', '铝型材与板材主供，来料需附材质证明'),
('S002', '苏州标准件厂', 'MATERIAL', '李敏', '13900002222', 'service@sz-fastener.local',
 '苏州市吴中区木渎镇金枫路 12 号', '91320506MA1CD5678Y', '月结 15 天', 5, 'B', '螺栓、密封圈等标准件，起订量低'),
('S003', '杭州外协喷涂中心', 'OUTSOURCE', '王海涛', '13800001111', null,
 '杭州市余杭区良渚工业园 3 号', null, '现结', 3, 'B', '外壳喷涂外协，按件计价')
on conflict (code) do nothing;

-- 5. 演示采购单：一张已确认、待收货的原料采购单
insert into purchase_order (order_no, supplier_id, warehouse_id, status, order_date, expected_date,
                            need_inspection, total_amount, remark, created_by, confirmed_by, confirmed_at)
select 'PO' || to_char(current_date - 1, 'YYYYMMDD') || '0001',
       s.id, w.id, 'CONFIRMED', current_date - 1, current_date + 3, true, 15000.00,
       '十月原料补库，型材与板材各一批', '计划-孙浩', '计划-孙浩', now() - interval '1 day'
from supplier s, warehouse w
where s.code = 'S001' and w.code = 'WH-RAW'
  and not exists (select 1 from purchase_order where status = 'CONFIRMED' and supplier_id = s.id)
limit 1;

insert into purchase_order_item (order_id, product_id, qty, price, amount, remark)
select o.id, p.id, v.qty, v.price, round(v.qty * v.price, 2), v.remark
from purchase_order o
join (values ('P003', 200.0, 44.5000, '6061-T6 型材'),
             ('P004', 100.0, 61.0000, '304 板材')) as v(code, qty, price, remark) on true
join product p on p.code = v.code
where o.remark = '十月原料补库，型材与板材各一批'
  and not exists (select 1 from purchase_order_item i where i.order_id = o.id);

-- 6. 权限：供应商与采购订单菜单 + 操作
insert into sys_role_permission (role_id, permission) values
(1, 'supplier'), (3, 'supplier'), (4, 'supplier'),
(1, 'purchase'), (3, 'purchase'), (4, 'purchase'),
(1, 'supplier:save'), (3, 'supplier:save'),
(1, 'purchase:create'), (3, 'purchase:create'),
(1, 'purchase:edit'), (3, 'purchase:edit'),
(1, 'purchase:confirm'), (3, 'purchase:confirm'),
(1, 'purchase:cancel'), (3, 'purchase:cancel'),
(1, 'purchase:delete'), (3, 'purchase:delete'),
(1, 'purchase:receive'), (3, 'purchase:receive'), (4, 'purchase:receive')
on conflict do nothing;
