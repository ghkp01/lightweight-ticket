-- 物料主数据补齐「默认来源」和「提前期」：自制/外购决定 MRP 生成工单还是采购申请
alter table product add column if not exists source varchar(8);
alter table product add column if not exists lead_days int not null default 0;

alter table product drop constraint if exists ck_product_category;
alter table product add constraint ck_product_category
    check (category in ('RAW', 'SEMI', 'FINISHED', 'PURCHASED'));

-- 历史数据按物料类型补默认来源：原料 / 外购件为外购，半成品 / 成品为自制
update product
   set source = case when category in ('RAW', 'PURCHASED') then 'BUY' else 'MAKE' end
 where source is null;

alter table product add constraint ck_product_source
    check (source is null or source in ('MAKE', 'BUY'));

-- 外购件没填提前期时给一个可用默认值，避免 MRP 倒排日期为空
update product
   set lead_days = 7
 where lead_days = 0 and category in ('RAW', 'PURCHASED');

comment on column product.source is '默认来源：MAKE 自制 / BUY 外购，MRP 据此决定生成工单还是采购申请';
comment on column product.lead_days is '提前期（天）：采购提前期或自制生产周期，MRP 倒排建议下单/开工日期';
