-- 只读视图补订单币种：PostgreSQL 的 select * 视图建好就固定列，新增列必须重建
-- 经营统计口径：本位币（CNY）；外币订单只在单据层记录，不参与销售金额汇总
create or replace view rm_sales_order as select * from sales_order;

comment on view rm_sales_order is '跨域只读模型：报表/看板/打印/导出只读，写入必须走 sales.api.SalesOrderPort';
