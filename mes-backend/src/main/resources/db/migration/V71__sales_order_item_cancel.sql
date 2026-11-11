-- 明细级撤单：客户只取消订单里的某几行，其余照做。
--
-- 之前只能整单取消，一条线不要就得把整张单撤了再重开，
-- 已排产的工单、已领的料、已占的产能全要重来一遍。
-- 这里给明细行加独立状态，撤行时连带作废它自己的工单。

alter table sales_order_item add column if not exists status varchar(16) not null default 'ACTIVE';
alter table sales_order_item add column if not exists cancel_reason varchar(255);
alter table sales_order_item add column if not exists cancelled_at timestamptz;
alter table sales_order_item add column if not exists cancelled_by varchar(64);

alter table sales_order_item drop constraint if exists ck_so_item_status;
alter table sales_order_item
    add constraint ck_so_item_status check (status in ('ACTIVE', 'CANCELLED'));

create index if not exists idx_so_item_status on sales_order_item (order_id, status);

-- 主表加列后读模型视图要重建才带上新列（新列只能追加在末尾）
create or replace view rm_sales_order_item as select * from sales_order_item;
