-- 工艺路线变更记录：谁在什么时候把工艺路线改成了什么

create table product_routing_change (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id) on delete cascade,
    action varchar(16) not null,
    old_summary varchar(500),
    new_summary varchar(500),
    remark varchar(255),
    changed_by varchar(64),
    changed_at timestamptz not null default now(),
    constraint ck_routing_change_action check (action in ('INIT', 'UPDATE', 'COPY'))
);

create index idx_routing_change_product on product_routing_change(product_id, changed_at desc);
