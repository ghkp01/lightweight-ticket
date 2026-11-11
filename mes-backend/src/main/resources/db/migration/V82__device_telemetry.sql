-- 设备采集网关落库表：现场网关（HTTP 上报）或人工补录的产量 / 停机分钟。
-- 设备模块自有表；OEE 会把这里的停机分钟并入「故障停机」一起算可用率。

create table if not exists device_telemetry (
    id bigserial primary key,
    device_code varchar(64) not null,
    work_center_id bigint references work_center(id),
    output_qty numeric(14,4) not null default 0,
    downtime_minutes numeric(14,2) not null default 0,
    source varchar(16) not null default 'MANUAL',
    recorded_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists idx_device_telemetry_center_time
    on device_telemetry (work_center_id, recorded_at);

comment on table device_telemetry is '设备采集数据：网关 HTTP 上报或人工补录的产量与停机分钟';
comment on column device_telemetry.source is 'MANUAL 人工补录 / GATEWAY 采集网关上报';
