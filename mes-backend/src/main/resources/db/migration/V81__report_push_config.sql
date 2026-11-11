-- 经营日报推送：把每日经营摘要推到飞书 / 钉钉 / 企业微信的群机器人。
-- 配置放在 sys_config，默认关闭、Webhook 地址按密文处理（只写不读）。

insert into sys_config (config_key, config_value, value_type, title, group_name, remark, options, sensitive)
values
  ('report.push.enabled', 'false', 'BOOLEAN', '启用经营日报推送', '经营日报',
   '开启后每天 08:30 自动把经营摘要推到群机器人；关闭则只允许手工推送', null, false),
  ('report.webhook.type', 'FEISHU', 'SELECT', '群机器人类型', '经营日报',
   'FEISHU=飞书，DINGTALK=钉钉，WECOM=企业微信', 'FEISHU,DINGTALK,WECOM', false),
  ('report.webhook.url', '', 'TEXT', '群机器人 Webhook 地址', '经营日报',
   '在飞书 / 钉钉 / 企业微信群里添加「自定义机器人」后复制 Webhook 地址', null, true)
on conflict (config_key) do nothing;
