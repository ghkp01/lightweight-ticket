-- 追溯方式从「产品布尔开关」升成「产品给默认 + 工单快照」
--
-- 原来的问题是实时读 product.serial_managed：产品开关中途一改，已下达的工单会立刻从
-- 「能报工」变成「不能报工」，但件码又不会补生成，工单直接卡死。而且同一物料卖给不同客户，
-- 追溯要求常常不一样（客户指定单件追溯 / 试制要追、量产不要），产品级一刀切不够用。
--
-- 所以：
--   1) 产品上给默认追溯方式 trace_mode（NONE / SERIAL，预留 LOT）；
--   2) 工单下达前从产品快照过来，未下达前计划可以在工单上改；
--   3) 所有判定改成读工单，产品改动不再影响在制工单；
--   4) serial_managed 保留为兼容列（等价于 trace_mode = 'SERIAL'），写入时由 trace_mode 派生。

-- ---------------------------------------------------------------- 1. 产品默认追溯方式
alter table product add column if not exists trace_mode varchar(16) not null default 'NONE';

update product set trace_mode = 'SERIAL' where serial_managed = true and trace_mode <> 'SERIAL';

alter table product drop constraint if exists ck_product_trace_mode;
alter table product add constraint ck_product_trace_mode
    check (trace_mode in ('NONE', 'SERIAL', 'LOT'));

comment on column product.trace_mode is
    '默认追溯方式：NONE 按数量报工 / SERIAL 单件（每件一码，逐道扫码）/ LOT 按批次（预留）';
comment on column product.serial_managed is
    '兼容列：等价于 trace_mode = SERIAL，由 trace_mode 派生，不要单独维护';

-- ---------------------------------------------------------------- 2. 工单快照
alter table work_order add column if not exists trace_mode varchar(16) not null default 'NONE';

update work_order w set trace_mode = p.trace_mode
from product p
where p.id = w.product_id and w.trace_mode <> p.trace_mode;

alter table work_order drop constraint if exists ck_work_order_trace_mode;
alter table work_order add constraint ck_work_order_trace_mode
    check (trace_mode in ('NONE', 'SERIAL', 'LOT'));

comment on column work_order.trace_mode is
    '本单的追溯方式：建单时从产品快照，下达前可由计划改；下达后按它生成件码与判定能否按数量报工';

create index if not exists idx_work_order_trace on work_order (trace_mode);

-- 主表加了列，读模型跟着扩（列只往后加）
create or replace view rm_work_order as select * from work_order;
create or replace view rm_product as select * from product;

-- ---------------------------------------------------------------- 3. 内置操作工角色
-- 之前演示账号 worker1 / worker2 复用「计划」角色，51 项权限里带着 cost / perf / perf:approve，
-- 工人登录能看到成本与绩效工资。这里补一个只够干活的角色，并把这两人挂过来。
insert into sys_role (code, name, description, data_scope, builtin)
values ('OPERATOR', '操作工',
        '车间一线：看工单、报工、领退料、扫码、看板；不看成本与绩效', 'ALL', true)
on conflict (code) do nothing;

insert into sys_role_permission (role_id, permission)
select r.id, p.permission
from sys_role r
cross join (values ('dashboard'), ('production'), ('production:report'),
                   ('production:issue'), ('production:return'), ('file:upload')) as p(permission)
where r.code = 'OPERATOR'
on conflict do nothing;

update sys_user set role = 'OPERATOR'
where username in ('worker1', 'worker2') and role = 'PLANNER';
