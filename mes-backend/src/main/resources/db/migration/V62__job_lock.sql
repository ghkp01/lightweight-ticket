-- 定时任务的跨节点互斥。
--
-- 原来用进程内 AtomicBoolean 加锁，单机没问题，集群下每个节点都会各跑一遍：
-- 交期预警重复推送、缺料巡检重复报安灯、审批超时重复提醒。
-- 改成数据库行锁：谁插进去谁执行，释放时按 owner 删；进程崩溃留下的行按超时自动接管。
create table if not exists sys_job_lock (
    code varchar(64) primary key,
    owner varchar(160) not null,
    acquired_at timestamptz not null default now()
);

comment on table sys_job_lock is '定时任务锁：集群里同一任务同一时刻只允许一个节点执行';
comment on column sys_job_lock.owner is '持有者标识：主机名+进程号，释放时按它校验，避免误删别人的锁';
comment on column sys_job_lock.acquired_at is '加锁时间，超过 stale_minutes 视为持有者已崩溃，可被接管';
