-- 工单依赖：半成品工单必须先完工，成品工单才能下达/开工
create table if not exists work_order_dependency (
    id bigserial primary key,
    predecessor_id bigint not null,
    successor_id bigint not null,
    created_at timestamp not null default now(),
    constraint ux_work_order_dependency unique (predecessor_id, successor_id),
    constraint ck_work_order_dependency_not_self check (predecessor_id <> successor_id)
);

create index if not exists ix_work_order_dependency_successor
    on work_order_dependency (successor_id);

create index if not exists ix_work_order_dependency_predecessor
    on work_order_dependency (predecessor_id);

comment on table work_order_dependency is
    '工单前后置关系：predecessor 完工后 successor 才能下达，用于半成品先于成品';
