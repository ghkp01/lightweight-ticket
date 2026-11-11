-- 自定义字段扩展到生产工单、物料档案、销售订单明细
alter table work_order add column if not exists ext_data jsonb not null default '{}'::jsonb;
alter table product add column if not exists ext_data jsonb not null default '{}'::jsonb;
alter table sales_order_item add column if not exists ext_data jsonb not null default '{}'::jsonb;

create index if not exists ix_work_order_ext_data on work_order using gin (ext_data);
create index if not exists ix_product_ext_data on product using gin (ext_data);
create index if not exists ix_sales_order_item_ext_data on sales_order_item using gin (ext_data);

comment on column work_order.ext_data is '自定义字段值，由 sys_custom_field 定义并校验';
comment on column product.ext_data is '自定义字段值，由 sys_custom_field 定义并校验';
comment on column sales_order_item.ext_data is '销售订单明细自定义字段值';
