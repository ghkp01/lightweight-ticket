-- 报价单：报价员报价 → 技术评审 → 转销售订单

create table sales_quote (
    id bigint generated always as identity primary key,
    quote_no varchar(32) not null unique,
    customer_id bigint not null references customer(id),
    contact varchar(64),
    valid_until date,
    delivery_date date,
    status varchar(16) not null default 'DRAFT',
    total_qty numeric(14,4) not null default 0,
    total_amount numeric(14,2) not null default 0,
    remark varchar(500),
    review_comment varchar(500),
    reviewed_by varchar(64),
    reviewed_at timestamptz,
    converted_order_id bigint,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_sales_quote_status check (status in
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CONVERTED', 'CANCELLED'))
);

create index ix_sales_quote_customer on sales_quote(customer_id);
create index ix_sales_quote_status on sales_quote(status);

create table sales_quote_item (
    id bigint generated always as identity primary key,
    quote_id bigint not null references sales_quote(id) on delete cascade,
    product_id bigint not null references product(id),
    product_code varchar(64) not null,
    product_name varchar(128) not null,
    spec varchar(128),
    unit varchar(16),
    qty numeric(14,4) not null,
    price numeric(14,4) not null default 0,
    amount numeric(14,2) not null default 0,
    remark varchar(255),
    constraint ck_sales_quote_item_qty check (qty > 0),
    constraint ck_sales_quote_item_price check (price >= 0)
);

create index ix_sales_quote_item_quote on sales_quote_item(quote_id);

create table sales_quote_review (
    id bigint generated always as identity primary key,
    quote_id bigint not null references sales_quote(id) on delete cascade,
    round_no int not null,
    reviewer varchar(64) not null,
    result varchar(8) not null,
    comment varchar(500),
    created_at timestamptz not null default now(),
    constraint ck_sales_quote_review_result check (result in ('PASS', 'REJECT'))
);

create index ix_sales_quote_review_quote on sales_quote_review(quote_id, round_no);

-- 订单回链来源报价单，便于追溯
alter table sales_order add column if not exists quote_id bigint;
create index if not exists ix_sales_order_quote on sales_order(quote_id) where quote_id is not null;

-- ---------------------------------------------------------------- 角色与权限

insert into sys_role (code, name, description, data_scope, builtin) values
    ('QUOTER', '报价员', '客户询价、报价单编制与转销售订单', 'SELF', true),
    ('TECH', '技术员', '订单 BOM 编制与 BOM 任务处理', 'ALL', true)
on conflict (code) do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values
    ('quotation'), ('quotation:create'), ('quotation:update'), ('quotation:submit'),
    ('quotation:review'), ('quotation:convert'), ('quotation:delete'), ('quotation:cancel')) as p(permission)
where r.code = 'ADMIN'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('dashboard'), ('customers'), ('products'),
                   ('quotation'), ('quotation:create'), ('quotation:update'),
                   ('quotation:submit'), ('quotation:convert'), ('quotation:cancel')) as p(permission)
where r.code = 'QUOTER'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('quotation')) as p(permission)
where r.code = 'SALES'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('dashboard'), ('products'), ('bom:save'), ('file:upload'),
                   ('quotation'), ('quotation:review'),
                   ('bom_task'), ('bom_task:complete')) as p(permission)
where r.code = 'TECH'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('bom_task'), ('bom_task:assign'), ('bom_task:cancel')) as p(permission)
where r.code = 'PLANNER'
on conflict do nothing;
