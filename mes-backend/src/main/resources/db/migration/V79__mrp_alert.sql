-- 物料缺口逾期预警：每天由定时任务扫一遍 MRP，把「已过建议下单日仍有缺口」的物料推给计划。
-- 按「日期 + 物料」唯一，同一天手工重跑也只推一次，不会刷屏。

create table if not exists mrp_alert_log (
    id           bigserial primary key,
    alert_date   date          not null,
    product_id   bigint        not null,
    product_code varchar(64),
    gap_qty      numeric(18, 4) not null,
    need_date    date,
    order_count  int           not null default 0,
    pushed_to    varchar(64),
    created_at   timestamptz   not null default now(),
    unique (alert_date, product_id)
);

create index if not exists idx_mrp_alert_date on mrp_alert_log (alert_date desc);
