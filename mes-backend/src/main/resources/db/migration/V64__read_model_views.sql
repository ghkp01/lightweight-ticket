-- 跨域只读模型（read model）
--
-- 背景：报表、看板、追溯、单据打印、数据导入导出、月结、成本、OEE、开放接口这些场景
-- 天然要同时读多个模块的数据（一张月结表要 join 采购、销售、发货、库存、主数据）。
-- 把它们逐个改成「先调 N 个契约再在内存里拼」既会退化成 N+1，也让这些只读场景绑架各模块的接口。
--
-- 所以划一条线：
--   1. 跨域 **写与状态变更** → 一律走各模块 api 包的契约（端口 + 命令方法）；
--   2. 跨域 **只读聚合** → 只允许读这里发布的 rm_* 视图，不允许直接 join 别人的表。
--
-- 视图定义刻意用 select *：列结构与主表完全一致，消费方的 SQL 只需把表名换成 rm_ 前缀，
-- 别名、条件、排序都不动，语义零变化。将来真的拆成微服务时，这一层换成
-- 「同步到报表库 / 读副本」即可，消费方的 SQL 仍然不用改。
--
-- 注意：PostgreSQL 在 create view 时会把 select * 展开成当时的列清单。
-- 主表后续新增列不会自动出现在视图里 —— 需要新列时在这里补一条
-- create or replace view（列只能往后加，不能删名），属于刻意的「读模型契约」演进节奏。

create or replace view rm_customer as select * from customer;
create or replace view rm_product as select * from product;
create or replace view rm_warehouse as select * from warehouse;
create or replace view rm_supplier as select * from supplier;

create or replace view rm_sales_order as select * from sales_order;
create or replace view rm_sales_order_item as select * from sales_order_item;

create or replace view rm_shipment as select * from shipment;
create or replace view rm_shipment_item as select * from shipment_item;

create or replace view rm_inventory as select * from inventory;
create or replace view rm_inventory_batch as select * from inventory_batch;
create or replace view rm_stock_txn as select * from stock_txn;

create or replace view rm_work_order as select * from work_order;
create or replace view rm_work_order_operation as select * from work_order_operation;
create or replace view rm_work_order_bom as select * from work_order_bom;
create or replace view rm_work_order_material_batch as select * from work_order_material_batch;

create or replace view rm_inspection as select * from inspection;

create or replace view rm_andon_call as select * from andon_call;

create or replace view rm_process as select * from process;
create or replace view rm_work_center as select * from work_center;

create or replace view rm_purchase_order as select * from purchase_order;
create or replace view rm_purchase_return as select * from purchase_return;
create or replace view rm_ap_accrual as select * from ap_accrual;
create or replace view rm_ap_invoice as select * from ap_invoice;
create or replace view rm_ap_payment as select * from ap_payment;

create or replace view rm_transfer_order as select * from transfer_order;
create or replace view rm_transfer_order_item as select * from transfer_order_item;

comment on view rm_sales_order is '跨域只读模型：报表/看板/打印/导出只读，写入必须走 sales.api.SalesOrderPort';
comment on view rm_work_order is '跨域只读模型：报表/看板/打印/导出只读，写入必须走 production.api.WorkOrderPort';
comment on view rm_stock_txn is '跨域只读模型：报表/成本/导出只读，写入必须走 inventory.api.InventoryPort';
comment on view rm_product is '跨域只读模型：报表/看板/导出只读，写入必须走 masterdata.api.MasterDataPort';
