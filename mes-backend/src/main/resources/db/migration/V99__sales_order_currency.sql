-- 销售订单币种：报价转单时把报价币种快照过来，手工建单默认人民币
alter table sales_order add column if not exists currency varchar(8) not null default 'CNY';

comment on column sales_order.currency is '订单币种：CNY/USD/EUR，报价转单时取自报价单';
