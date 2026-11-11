-- 移动端离线队列：车间断网时扫码/报工先存手机本地，恢复网络后按顺序重传。
-- 重传靠幂等键兜底：同一个 X-Idempotency-Key 只真正执行一次，重复到达直接返回上次结果，
-- 避免「手机以为失败、实际服务端已成功」导致的重复报工与重复库存流水。
create table if not exists sys_offline_key (
    id bigint generated always as identity primary key,
    idem_key varchar(80) not null,
    user_name varchar(64),
    method varchar(10),
    path varchar(255),
    status_code int not null default 200,
    response_body text,
    created_at timestamptz not null default now(),
    constraint uk_offline_key unique (idem_key)
);

create index if not exists idx_offline_key_created on sys_offline_key (created_at);

comment on table sys_offline_key is '离线重传幂等键：同一 key 只执行一次，重放直接返回上次结果';
comment on column sys_offline_key.response_body is '首次执行的响应体快照，重放时原样返回';

insert into sys_role_permission (role_id, permission) values
(1, 'system_offline')
on conflict do nothing;

insert into sys_config (config_key, config_value, value_type, title, group_name, remark) values
('offline.sync.enabled', 'true', 'BOOLEAN', '启用移动端离线队列', '接口',
 '关闭后移动端断网时不再本地排队，直接提示网络错误'),
('offline.sync.queue.limit', '200', 'NUMBER', '单机离线队列上限', '接口',
 '移动端本地最多缓存多少条待重传请求，超出后不再接收新记录'),
('offline.sync.retention.days', '7', 'NUMBER', '离线重传记录保留天数', '接口',
 '超过天数的幂等键由「离线重传记录清理」任务删除')
on conflict (config_key) do nothing;
