-- 应收发票的跨域只读模型：报表/月结只读 rm_*，不直接读写财务模块的表
create or replace view rm_ar_invoice as select * from ar_invoice;

comment on view rm_ar_invoice is '跨域只读模型：报表/月结读取销售发票，写入必须走 finance 模块';
