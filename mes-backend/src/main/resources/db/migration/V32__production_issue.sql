-- 生产领料单化：领料从「直接扣库存」升级为「先生成领料单，再按单扣减」
-- 1) 领料单主表 / 明细表
-- 2) 库存流水回指领料单，便于单据与流水互查
-- 3) 工单 BOM 区分「主料实领」与「替代料补量」
-- 4) 出库单可挂工单，承接车间非 BOM 领用

create table if not exists production_issue (
    id bigint generated always as identity primary key,
    issue_no varchar(32) not null unique,
    work_order_id bigint not null references work_order(id),
    warehouse_id bigint not null references warehouse(id),
    issuer varchar(40) not null,
    team varchar(40),
    operator varchar(64),
    source varchar(16) not null default 'PC',
    remark varchar(200),
    total_qty numeric(18, 4) not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_issue_source check (source in ('PC', 'SCAN'))
);

comment on table production_issue is '生产领料单：一张单对应一次领料动作，可包含多行物料';
comment on column production_issue.issuer is '实际领料人（车间填写），与登录账号 operator 区分';
comment on column production_issue.source is 'PC 工单页领料 / SCAN 车间扫码领料';

create index if not exists idx_production_issue_work on production_issue (work_order_id);
create index if not exists idx_production_issue_created on production_issue (created_at);

create table if not exists production_issue_item (
    id bigint generated always as identity primary key,
    issue_id bigint not null references production_issue(id) on delete cascade,
    material_id bigint not null references product(id),
    qty numeric(18, 4) not null,
    alternative_for bigint references product(id),
    batch_summary varchar(200),
    constraint ck_issue_item_qty check (qty > 0)
);

comment on column production_issue_item.alternative_for is '该行是替代料时，记录被替代的主料；主料行为空';
comment on column production_issue_item.batch_summary is '本次扣减的批次摘要，例如 B2401×3 / B2402×2';

create index if not exists idx_production_issue_item_issue on production_issue_item (issue_id);

-- 库存流水回指领料单
alter table stock_txn add column if not exists issue_id bigint references production_issue(id);
create index if not exists idx_stock_txn_issue on stock_txn (issue_id);

-- 工单 BOM：issued_qty 保持「该行已满足量（含替代）」语义，新增 sub_issued_qty 记录其中由替代料补的部分
alter table work_order_bom add column if not exists sub_issued_qty numeric(18, 4) not null default 0;

comment on column work_order_bom.sub_issued_qty is '已领用数量中由替代料补的部分，主料实领 = issued_qty - sub_issued_qty';

-- 出库单：挂工单，用于车间非 BOM 物料领用归集
alter table outbound_order add column if not exists work_order_id bigint references work_order(id);
alter table outbound_order drop constraint if exists ck_outbound_type;
alter table outbound_order add constraint ck_outbound_type
    check (type in ('MANUAL', 'SCRAP', 'OTHER', 'ISSUE_OTHER'));
create index if not exists idx_outbound_order_work on outbound_order (work_order_id);

comment on column outbound_order.work_order_id is '关联工单：车间领用非 BOM 物料时填写，用于归集工单成本';
