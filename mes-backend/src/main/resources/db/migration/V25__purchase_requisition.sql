-- 采购申请与审批：需求先立单，按金额分级审批，批准后部分或全部转采购单

create table purchase_requisition (
    id bigint generated always as identity primary key,
    req_no varchar(32) not null unique,
    source_type varchar(16) not null default 'MANUAL',
    requester varchar(64) not null,
    department varchar(64),
    work_order_id bigint references work_order(id),
    need_date date,
    purpose varchar(255),
    status varchar(16) not null default 'DRAFT',
    total_amount numeric(18,2) not null default 0,
    submit_at timestamptz,
    approved_by varchar(64),
    approved_at timestamptz,
    reject_reason varchar(255),
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_req_source check (source_type in ('PRODUCTION', 'STOCK', 'MANUAL', 'URGENT')),
    constraint ck_req_status check (status in
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL', 'CONVERTED', 'REJECTED', 'CANCELLED'))
);
comment on table purchase_requisition is '采购申请：谁要买、为什么买、多少钱，按金额分级审批';
comment on column purchase_requisition.source_type is 'PRODUCTION 工单缺料 / STOCK 库存补货 / MANUAL 非生产 / URGENT 紧急采购';
comment on column purchase_requisition.status is 'DRAFT 草稿 / SUBMITTED 待审批 / APPROVED 已批准 / PARTIAL 部分转单 / CONVERTED 已转单 / REJECTED 已驳回 / CANCELLED 已取消';

create table purchase_requisition_item (
    id bigint generated always as identity primary key,
    req_id bigint not null references purchase_requisition(id) on delete cascade,
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    converted_qty numeric(18,4) not null default 0,
    target_price numeric(18,4) not null default 0,
    suggest_supplier_id bigint references supplier(id),
    need_quote boolean not null default false,
    remark varchar(255),
    constraint ck_req_item_qty check (qty > 0),
    constraint ck_req_item_converted check (converted_qty >= 0 and converted_qty <= qty)
);
create index idx_req_item_req on purchase_requisition_item (req_id);
create index idx_req_status on purchase_requisition (status, created_at desc);

-- 采购单回链申请：转单时带过去，取消或删除采购单时把已转数量退回申请
alter table purchase_order add column if not exists requisition_id bigint references purchase_requisition(id);
alter table purchase_order_item add column if not exists requisition_item_id bigint references purchase_requisition_item(id);
create index idx_po_requisition on purchase_order (requisition_id);

-- 审批阈值：单张申请金额超过这个值需要管理员审批
insert into sys_config (config_key, config_value, value_type, title, group_name, remark)
values ('purchase.approval.threshold', '5000', 'NUMBER', '采购申请审批阈值', '采购',
        '单张申请金额不超过该值由计划主管审批，超过则需要管理员审批；单位：元')
on conflict (config_key) do nothing;

insert into sys_role_permission (role_id, permission) values
(1, 'requisition'), (3, 'requisition'), (4, 'requisition'), (5, 'requisition'),
(1, 'purchase:request'), (3, 'purchase:request'), (4, 'purchase:request'), (5, 'purchase:request'),
(1, 'purchase:approve'), (3, 'purchase:approve')
on conflict do nothing;

-- 演示申请：计划提的一张标准件补库申请，待审批
insert into purchase_requisition (req_no, source_type, requester, department, need_date, purpose,
                                  status, total_amount, submit_at, remark)
select 'PR' || to_char(current_date, 'YYYYMMDD') || '0001', 'STOCK', 'planner', '计划部',
       current_date + 4, '标准件与密封圈补库', 'SUBMITTED', 2900.00, now() - interval '2 hour',
       '安全库存低于下限，先补一批'
where not exists (select 1 from purchase_requisition where req_no = 'PR' || to_char(current_date, 'YYYYMMDD') || '0001');

insert into purchase_requisition_item (req_id, product_id, qty, target_price, suggest_supplier_id, need_quote, remark)
select r.id, p.id, v.qty, v.price, s.id, v.need_quote, v.remark
from purchase_requisition r
join (values ('P005', 2000.0, 0.8000, true, '常用规格，询一次价'),
             ('P006', 1000.0, 1.3000, false, '上次价格可延用')) as v(code, qty, price, need_quote, remark) on true
join product p on p.code = v.code
left join supplier s on s.code = 'S002'
where r.req_no = 'PR' || to_char(current_date, 'YYYYMMDD') || '0001'
  and not exists (select 1 from purchase_requisition_item i where i.req_id = r.id);
