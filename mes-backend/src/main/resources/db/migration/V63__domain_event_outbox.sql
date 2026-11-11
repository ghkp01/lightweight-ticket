-- 领域事件 Outbox。
--
-- 现在模块之间大量直接注入对方的 Service（例如仓储入库完直接调采购回写实收），
-- 这种「同进程同步调用」拆成微服务时全都要重写。这里先把跨模块的副作用改成事件：
-- 发布方只声明「发生了什么」，谁关心谁来订阅，发布方不再 import 消费方的类。
--
-- 当前实现是「落库留痕 + 同进程同步投递」，保证行为和原来一致（同一个事务里抛错一起回滚）。
-- 将来接 MQ 时只需要把投递换成发消息，并由中继任务推进 status，业务代码不用改。
create table if not exists sys_domain_event (
    id bigint generated always as identity primary key,
    event_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(128) not null,
    payload jsonb not null default '{}'::jsonb,
    status varchar(16) not null default 'SENT',
    error varchar(500),
    created_at timestamptz not null default now(),
    handled_at timestamptz
);

comment on table sys_domain_event is '领域事件 Outbox：跨模块副作用的落库留痕，将来可直接中继到 MQ';
comment on column sys_domain_event.event_type is '事件类型，订阅方按它匹配，例如 INBOUND_COMPLETED';
comment on column sys_domain_event.aggregate_type is '聚合根类型，例如 INBOUND_ORDER';
comment on column sys_domain_event.aggregate_id is '聚合根 id 或单号，用于排查与重放';
comment on column sys_domain_event.status is 'SENT 已投递 / FAILED 投递失败 / PENDING 待投递';
comment on column sys_domain_event.handled_at is '投递完成时间';

create index if not exists idx_domain_event_type on sys_domain_event (event_type, created_at desc);
create index if not exists idx_domain_event_status on sys_domain_event (status) where status <> 'SENT';
