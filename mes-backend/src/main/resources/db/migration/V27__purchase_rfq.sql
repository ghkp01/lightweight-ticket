-- 询价比价：申请中需要询价的行生成询价单，多供应商报价、比价选定，价格沉淀到供应商价格库

create table purchase_rfq (
    id bigint generated always as identity primary key,
    rfq_no varchar(32) not null unique,
    requisition_id bigint references purchase_requisition(id),
    status varchar(16) not null default 'OPEN',
    deadline date,
    selected_quote_id bigint,
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_rfq_status check (status in ('OPEN', 'QUOTED', 'SELECTED', 'CANCELLED'))
);
comment on table purchase_rfq is '询价单：一行或多行申请明细，向多家供应商询价后比价选定';
comment on column purchase_rfq.status is 'OPEN 待报价 / QUOTED 已报价 / SELECTED 已选定 / CANCELLED 已取消';

create table purchase_rfq_item (
    id bigint generated always as identity primary key,
    rfq_id bigint not null references purchase_rfq(id) on delete cascade,
    requisition_item_id bigint references purchase_requisition_item(id),
    product_id bigint not null references product(id),
    qty numeric(18,4) not null,
    remark varchar(255),
    constraint ck_rfq_item_qty check (qty > 0)
);
create index idx_rfq_item_rfq on purchase_rfq_item (rfq_id);

create table purchase_quote (
    id bigint generated always as identity primary key,
    rfq_id bigint not null references purchase_rfq(id) on delete cascade,
    supplier_id bigint not null references supplier(id),
    lead_days int not null default 7,
    payment_term varchar(64),
    valid_until date,
    total_amount numeric(18,2) not null default 0,
    status varchar(16) not null default 'SUBMITTED',
    remark varchar(255),
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_quote_status check (status in ('SUBMITTED', 'SELECTED', 'REJECTED')),
    constraint ux_quote_supplier unique (rfq_id, supplier_id)
);
comment on table purchase_quote is '供应商报价：同一询价单同一供应商只保留一份，重新录入即覆盖';

create table purchase_quote_item (
    id bigint generated always as identity primary key,
    quote_id bigint not null references purchase_quote(id) on delete cascade,
    rfq_item_id bigint not null references purchase_rfq_item(id) on delete cascade,
    price numeric(18,4) not null default 0,
    amount numeric(18,2) not null default 0,
    remark varchar(255),
    constraint ck_quote_item_price check (price >= 0),
    constraint ux_quote_item unique (quote_id, rfq_item_id)
);
create index idx_quote_item_quote on purchase_quote_item (quote_id);

create table supplier_price (
    id bigint generated always as identity primary key,
    supplier_id bigint not null references supplier(id),
    product_id bigint not null references product(id),
    price numeric(18,4) not null,
    lead_days int not null default 7,
    valid_from date not null default current_date,
    valid_to date,
    source varchar(16) not null default 'RFQ',
    source_no varchar(32),
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_price_source check (source in ('RFQ', 'MANUAL', 'PO'))
);
create index idx_supplier_price_lookup on supplier_price (supplier_id, product_id, valid_from desc);
comment on table supplier_price is '供应商价格库：询价选定后沉淀，下单时可作为参考价';

-- 询价单号流水：演示数据占用 0001，这里对齐
insert into code_seq (prefix, seq_date, seq)
values ('RFQ', current_date, 1)
on conflict (prefix, seq_date) do update set seq = greatest(code_seq.seq, 1);

insert into sys_role_permission (role_id, permission) values
(1, 'rfq'), (3, 'rfq'), (4, 'rfq'),
(1, 'purchase:rfq'), (3, 'purchase:rfq'),
(1, 'purchase:select_quote'), (3, 'purchase:select_quote')
on conflict do nothing;

-- 演示询价：申请 PR 当天 0001 里的标准螺栓需要询价，已收到两家报价，等待选定
insert into purchase_rfq (rfq_no, requisition_id, status, deadline, remark, created_by)
select 'RFQ' || to_char(current_date, 'YYYYMMDD') || '0001', r.id, 'QUOTED', current_date + 3,
       '标准螺栓年度询价', 'planner'
from purchase_requisition r
where r.req_no = 'PR' || to_char(current_date, 'YYYYMMDD') || '0001'
  and not exists (select 1 from purchase_rfq where rfq_no = 'RFQ' || to_char(current_date, 'YYYYMMDD') || '0001');

insert into purchase_rfq_item (rfq_id, requisition_item_id, product_id, qty, remark)
select f.id, i.id, i.product_id, i.qty - i.converted_qty, i.remark
from purchase_rfq f
join purchase_requisition r on r.id = f.requisition_id
join purchase_requisition_item i on i.req_id = r.id and i.need_quote
where f.rfq_no = 'RFQ' || to_char(current_date, 'YYYYMMDD') || '0001'
  and not exists (select 1 from purchase_rfq_item x where x.rfq_id = f.id);

insert into purchase_quote (rfq_id, supplier_id, lead_days, payment_term, valid_until, total_amount, status, created_by)
select f.id, s.id, v.lead_days, v.payment_term, current_date + 30,
       (select round(coalesce(sum(fi.qty * v.price), 0), 2) from purchase_rfq_item fi where fi.rfq_id = f.id),
       'SUBMITTED', 'planner'
from purchase_rfq f
join (values ('S001', 0.7800, 7, '月结 30 天'),
             ('S002', 0.8200, 5, '月结 15 天')) as v(code, price, lead_days, payment_term) on true
join supplier s on s.code = v.code
where f.rfq_no = 'RFQ' || to_char(current_date, 'YYYYMMDD') || '0001'
  and not exists (select 1 from purchase_quote q where q.rfq_id = f.id and q.supplier_id = s.id);

insert into purchase_quote_item (quote_id, rfq_item_id, price, amount)
select q.id, fi.id, v.price, round(fi.qty * v.price, 2)
from purchase_rfq f
join purchase_quote q on q.rfq_id = f.id
join supplier s on s.id = q.supplier_id
join purchase_rfq_item fi on fi.rfq_id = f.id
join (values ('S001', 0.7800), ('S002', 0.8200)) as v(code, price) on v.code = s.code
where f.rfq_no = 'RFQ' || to_char(current_date, 'YYYYMMDD') || '0001'
  and not exists (select 1 from purchase_quote_item x where x.quote_id = q.id and x.rfq_item_id = fi.id);
