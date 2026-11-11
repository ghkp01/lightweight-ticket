-- 生产退料单化：领多的料要能「开单退回」而不是只改个数
-- 1) 退料单主表 / 明细表（单号 MR 前缀），记录退料人、仓库、原因
-- 2) 库存流水回指退料单，单据与流水双向可查
-- 3) 退料按原领料批次回库，批次号不被覆盖，成品->原料的追溯链不断

create table if not exists production_return (
    id bigint generated always as identity primary key,
    return_no varchar(32) not null unique,
    work_order_id bigint not null references work_order(id),
    warehouse_id bigint not null references warehouse(id),
    returner varchar(40) not null,
    team varchar(40),
    operator varchar(64),
    source varchar(16) not null default 'PC',
    reason varchar(200),
    total_qty numeric(18, 4) not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_return_source check (source in ('PC', 'SCAN'))
);

comment on table production_return is '生产退料单：一张单对应一次退料动作，可包含多行物料';
comment on column production_return.returner is '实际退料人（车间填写），与登录账号 operator 区分';
comment on column production_return.reason is '退料原因，如领多、图纸变更、订单减量';

create index if not exists idx_production_return_work on production_return (work_order_id);
create index if not exists idx_production_return_created on production_return (created_at);

create table if not exists production_return_item (
    id bigint generated always as identity primary key,
    return_id bigint not null references production_return(id) on delete cascade,
    material_id bigint not null references product(id),
    warehouse_id bigint references warehouse(id),
    qty numeric(18, 4) not null,
    batch_summary varchar(200),
    constraint ck_return_item_qty check (qty > 0)
);

comment on column production_return_item.warehouse_id is '该行实际退回的仓库；为空表示与退料单主仓库一致';
comment on column production_return_item.batch_summary is '本次退回的批次摘要，例如 B2401×3 / B2402×2';

create index if not exists idx_production_return_item_return on production_return_item (return_id);

-- 库存流水回指退料单
alter table stock_txn add column if not exists return_id bigint references production_return(id);
create index if not exists idx_stock_txn_return on stock_txn (return_id);

-- 退料单号规则
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle)
values ('MR', '生产退料单', 'MR', 'yyyyMMdd', '', 4, 'DAY')
on conflict (biz_type) do nothing;
