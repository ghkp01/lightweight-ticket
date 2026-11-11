-- 单件序列号（SN）管理：按件生成唯一码，扫码流转，记录每件当前工序与经手人
-- 只对产品加了「单件管理」开关的工单生效，批量零件工单维持原有按数量报工

alter table product add column if not exists serial_managed boolean not null default false;

comment on column product.serial_managed is '是否按单件管理：为真时该产品的工单下达会按计划数量生成件码';

create table if not exists work_order_unit (
    id bigint generated always as identity primary key,
    work_order_id bigint not null references work_order(id) on delete cascade,
    unit_no varchar(48) not null unique,
    seq_no int not null,
    status varchar(16) not null default 'PENDING',
    current_operation_seq int,
    current_operation_id bigint references work_order_operation(id),
    last_action_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_unit_status check (status in ('PENDING', 'IN_PROGRESS', 'FINISHED', 'SCRAPPED')),
    constraint uk_unit_work_seq unique (work_order_id, seq_no)
);

comment on table work_order_unit is '工单件码：一个产品一件一行，扫码流转的载体';
comment on column work_order_unit.unit_no is '件码，格式 工单号-序号，例如 WO202610220001-003';
comment on column work_order_unit.current_operation_seq is '当前所在工序序号；已完工或报废时为空';
comment on column work_order_unit.status is 'PENDING 待投产 / IN_PROGRESS 在制 / FINISHED 已完工 / SCRAPPED 已报废';

create index if not exists idx_unit_work on work_order_unit (work_order_id, seq_no);
create index if not exists idx_unit_status on work_order_unit (status);

create table if not exists work_order_unit_log (
    id bigint generated always as identity primary key,
    unit_id bigint not null references work_order_unit(id) on delete cascade,
    work_order_id bigint not null references work_order(id) on delete cascade,
    operation_seq int,
    operation_id bigint references work_order_operation(id),
    action varchar(16) not null,
    worker varchar(64),
    operator varchar(64),
    remark varchar(200),
    created_at timestamptz not null default now(),
    constraint ck_unit_log_action check (action in ('START', 'FINISH', 'SCRAP'))
);

comment on table work_order_unit_log is '件流转日志：每件在每道工序的开工、完工、报废记录';
comment on column work_order_unit_log.worker is '实际操作工（扫码时填写），与登录账号 operator 区分';

create index if not exists idx_unit_log_unit on work_order_unit_log (unit_id, id);
create index if not exists idx_unit_log_work on work_order_unit_log (work_order_id, id);
