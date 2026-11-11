-- 报价评审时限：公司规定天数与客户交期前置天数同时生效，取更早的一个

alter table sales_quote add column if not exists review_due_date date;
comment on column sales_quote.review_due_date is '本轮评审截止日期，提交评审时计算';

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
    ('quotation.review.due_days', '3', 'NUMBER', '报价评审时限（天）', '报价管理',
     '公司规定：提交评审后多少天内必须完成'),
    ('quotation.review.delivery_offset_days', '2', 'NUMBER', '评审距交期提前天数', '报价管理',
     '客户交期前多少天必须完成评审；与公司时限同时生效，取更早的日期')
on conflict (config_key) do nothing;
