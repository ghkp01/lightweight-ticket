-- 工单作废 + 订单取消留痕
--
-- 场景：客户下单后又改主意。原来订单一旦转生产就卡死 ——
-- 订单取消只放行草稿/已确认，工单又只能「已完工后关闭」，未开工的工单撤不回来。
-- 这里补两件事：工单可以作废（CANCELLED）、订单在工单全部收尾后可以取消。

alter table work_order add column if not exists cancel_reason varchar(255);
alter table work_order add column if not exists cancelled_at timestamptz;
alter table work_order add column if not exists cancelled_by varchar(64);

comment on column work_order.cancel_reason is '作废原因：客户取消、下单错误等';
comment on column work_order.cancelled_at is '作废时间，空表示未作废';
comment on column work_order.cancelled_by is '作废人';

create index if not exists idx_work_order_cancelled on work_order (cancelled_at)
    where cancelled_at is not null;

alter table sales_order add column if not exists cancel_reason varchar(255);
comment on column sales_order.cancel_reason is '取消原因';

-- 跨模块只读视图补齐新列（视图列只能往后加，用 select * 重建）
create or replace view rm_work_order as select * from work_order;
create or replace view rm_sales_order as select * from sales_order;

-- 权限：作废工单归计划与管理员，车间操作工不能撤单
insert into sys_role_permission (role_id, permission)
select r.id, 'production:cancel' from sys_role r
where r.code in ('ADMIN', 'PLANNER')
on conflict do nothing;
