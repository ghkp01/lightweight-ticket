-- 报价单商务字段：币种、税率、含税口径、付款条件、未税/税额拆分

alter table sales_quote add column if not exists currency varchar(8) not null default 'CNY';
alter table sales_quote add column if not exists tax_rate numeric(6,4) not null default 0.1300;
alter table sales_quote add column if not exists price_includes_tax boolean not null default true;
alter table sales_quote add column if not exists payment_terms varchar(200);
alter table sales_quote add column if not exists net_amount numeric(14,2) not null default 0;
alter table sales_quote add column if not exists tax_amount numeric(14,2) not null default 0;

comment on column sales_quote.currency is '币种：CNY/USD/EUR';
comment on column sales_quote.tax_rate is '税率，0.13 表示 13%';
comment on column sales_quote.price_includes_tax is 'true=单价含税，false=单价未税';
comment on column sales_quote.payment_terms is '付款条件';
comment on column sales_quote.net_amount is '未税金额';
comment on column sales_quote.tax_amount is '税额';
comment on column sales_quote.total_amount is '价税合计';

-- 报价员需要上传图纸/报价附件
insert into sys_role_permission (role_id, permission)
select r.id, 'file:upload'
from sys_role r
where r.code = 'QUOTER'
on conflict do nothing;
