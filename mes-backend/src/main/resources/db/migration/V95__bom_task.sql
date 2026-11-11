-- BOM 任务：计划员把订单明细派给技术员做 BOM，MRP 以 BOM 就绪为门槛

create table bom_task (
    id bigint generated always as identity primary key,
    sales_order_id bigint not null,
    sales_order_item_id bigint not null,
    product_id bigint not null,
    assignee_id bigint not null,
    assigned_by varchar(64),
    due_date date,
    status varchar(16) not null default 'PENDING',
    bom_id bigint,
    remark varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    finished_at timestamptz,
    constraint ck_bom_task_status check (status in ('PENDING', 'DONE', 'CANCELLED'))
);

create index ix_bom_task_assignee on bom_task(assignee_id, status);
create index ix_bom_task_order on bom_task(sales_order_id, sales_order_item_id);
-- 同一订单明细同时只能有一个未完成任务，避免重复派工
create unique index uk_bom_task_pending_item on bom_task(sales_order_item_id)
    where status = 'PENDING';

-- 计划员派工、技术员完成
insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('bom_task'), ('bom_task:assign'), ('bom_task:complete'),
                   ('bom_task:cancel')) as p(permission)
where r.code = 'ADMIN'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('bom_task'), ('bom_task:assign'), ('bom_task:cancel')) as p(permission)
where r.code = 'PLANNER'
on conflict do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('bom_task'), ('bom_task:complete')) as p(permission)
where r.code = 'TECH'
on conflict do nothing;
