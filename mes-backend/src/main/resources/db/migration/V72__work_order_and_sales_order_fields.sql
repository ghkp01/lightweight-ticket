-- 建单字段补齐：工单补备注，销售订单补客户 PO 号 / 联系人 / 交货地址 / 跟单人。
--
-- 原来手工建工单只有「产品 + 数量 + 两个日期」，工艺路线和 BOM 只能建完再进去看；
-- 销售订单也只有客户、日期、优先级、备注，客户 PO 号、送货地址、跟单的人都没地方记，
-- 打印与对账时要靠备注凑。这里把常用字段落到表上。

alter table work_order add column if not exists remark varchar(500);

alter table sales_order add column if not exists customer_po_no varchar(64);
alter table sales_order add column if not exists contact varchar(64);
alter table sales_order add column if not exists delivery_address varchar(255);
alter table sales_order add column if not exists sales_owner varchar(64);

-- 主表加列后读模型视图要重建才带上新列（新列只能追加在末尾）
create or replace view rm_work_order as select * from work_order;
create or replace view rm_sales_order as select * from sales_order;
