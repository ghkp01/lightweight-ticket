-- 工序工价与安灯异常

alter table work_order_operation add column unit_price numeric(12,2) not null default 0;

update work_order_operation
set unit_price = case seq when 1 then 1.20 when 2 then 2.50 else 2.00 end
where unit_price = 0;

create table andon_call (
    id bigint generated always as identity primary key,
    call_no varchar(32) not null unique,
    work_order_id bigint references work_order(id),
    work_order_no varchar(32),
    operation_name varchar(64),
    type varchar(24) not null,
    description varchar(500),
    status varchar(16) not null default 'OPEN',
    caller varchar(64),
    handler varchar(64),
    created_at timestamptz not null default now(),
    acked_at timestamptz,
    resolved_at timestamptz
);

create index idx_andon_status on andon_call(status, created_at desc);
