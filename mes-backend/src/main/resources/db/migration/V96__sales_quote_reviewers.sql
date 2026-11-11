-- 报价评审会签：指定评审人，全部通过才算通过；任一驳回即结束本轮

create table sales_quote_reviewer (
    id bigint generated always as identity primary key,
    quote_id bigint not null references sales_quote(id) on delete cascade,
    reviewer_id bigint not null,
    status varchar(8) not null default 'PENDING',
    comment varchar(500),
    reviewed_at timestamptz,
    constraint ck_sales_quote_reviewer_status check (status in ('PENDING', 'PASS', 'REJECT')),
    constraint uk_sales_quote_reviewer unique (quote_id, reviewer_id)
);

create index ix_sales_quote_reviewer_pending on sales_quote_reviewer(quote_id, status);

-- 一张报价单只能转一张订单，避免并发重复转单
create unique index uk_sales_quote_converted_order on sales_quote(converted_order_id)
    where converted_order_id is not null;

-- 同一个报价单不能对应多张订单
create unique index uk_sales_order_quote on sales_order(quote_id)
    where quote_id is not null;
