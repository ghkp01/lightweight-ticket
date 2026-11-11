-- 第 0 步数据库治理：补高频查询索引 + 可配置的数据保留天数。
-- 索引全部 if not exists，重复执行安全。

create index if not exists idx_work_report_reported on work_report (reported_at);
create index if not exists idx_work_report_work on work_report (work_order_id, reported_at);
create index if not exists idx_inspection_inspected on inspection (inspected_at);
create index if not exists idx_stock_txn_created on stock_txn (created_at);

insert into sys_config (config_key, config_value, value_type, title, group_name, remark, options, sensitive)
values
  ('retention.telemetry.days', '180', 'NUMBER', '设备采集保留天数', '数据保留',
   '原始采集只保留最近 N 天，超期由每天 03:30 的「历史数据保留清理」任务删除', null, false),
  ('retention.operLog.days', '365', 'NUMBER', '操作日志保留天数', '数据保留',
   '操作日志保留最近 N 天，满足审计追溯即可', null, false),
  ('retention.unitLog.days', '730', 'NUMBER', '单件追溯日志保留天数', '数据保留',
   '单件流转日志涉及质量追溯，默认保留 2 年，按客户要求调整', null, false),
  ('retention.jobLog.days', '90', 'NUMBER', '任务日志保留天数', '数据保留',
   '定时任务执行日志保留最近 N 天', null, false)
on conflict (config_key) do nothing;
