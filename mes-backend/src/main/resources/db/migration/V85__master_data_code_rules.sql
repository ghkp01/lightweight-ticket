-- 主数据编码规则：新建时可留空由系统生成
-- 主数据编码不带日期段、不重置流水，保持短编码；前缀与位数仍可在「编码规则」页调整
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle) values
('WC',  '工作中心', 'WC',  '', '', 3, 'NEVER'),
('OP',  '工序',     'OP',  '', '', 3, 'NEVER'),
('WH',  '仓库',     'WH',  '', '', 3, 'NEVER'),
('PD',  '产品',     'PD',  '', '', 4, 'NEVER'),
('CUS', '客户',     'CUS', '', '', 3, 'NEVER'),
('SUP', '供应商',   'SUP', '', '', 3, 'NEVER')
on conflict (biz_type) do nothing;
