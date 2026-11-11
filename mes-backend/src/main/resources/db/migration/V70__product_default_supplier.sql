-- 产品默认供应商：采购下单时自动带出，省掉每次手选。
-- 只在新单没指定供应商时兜底，不影响手工改选。
alter table product add column if not exists default_supplier_id bigint references supplier(id);

create index if not exists idx_product_default_supplier on product (default_supplier_id);

-- rm_product 是 select * 展开后的固定列清单，主表加列后必须重建才带上新列（新列只能追加在末尾）
create or replace view rm_product as select * from product;
