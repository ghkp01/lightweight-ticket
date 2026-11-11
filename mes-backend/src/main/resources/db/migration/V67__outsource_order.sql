-- 工序级外协：工单中途把某道工序发外协厂做，料从本厂仓发出，回来登记收回
--
-- 之前只有「标签」没有单据：work_center.type = OUTSOURCE 只让排产不占产能，
-- 供应商 category = OUTSOURCE 只是档案分类，工单工序上没有任何外协字段，
-- 于是「中途外协」只能改工序名写备注，料还被整单领料一起领走。
--
-- 这次补三件事：
--   1) work_order_operation 上加外协属性（谁做、什么价、发没发、回没回）；
--   2) 外协加工单 outsource_order / outsource_order_item —— 发出与收回是同一张单的两条腿；
--   3) 工单成本加 outsource_cost，外协费不再无处落地。

-- ---------------------------------------------------------------- 1. 工序外协属性
alter table work_order_operation
    add column if not exists outsource_supplier_id bigint references supplier(id),
    add column if not exists outsource_status varchar(16),
    add column if not exists outsource_price numeric(12, 2) not null default 0,
    add column if not exists outsource_expected_at date,
    add column if not exists outsource_sent_at timestamptz,
    add column if not exists outsource_received_qty numeric(18, 4) not null default 0,
    add column if not exists outsource_defect_qty numeric(18, 4) not null default 0;

comment on column work_order_operation.outsource_status is
    '外协状态：空=厂内做；PENDING_SEND=已转外协待发出；SENT=已发出在外部加工；RECEIVED=已收回';
comment on column work_order_operation.outsource_supplier_id is '外协供应商；PENDING_SEND 时允许为空，发出时必填';
comment on column work_order_operation.outsource_price is '外协单价（不含料），外协费 = 收回合格数 × 单价';
comment on column work_order_operation.outsource_expected_at is '约定回厂日期，超期在看板与大屏上报警';
comment on column work_order_operation.outsource_received_qty is '累计收回数量，跨多张外协单累加';
comment on column work_order_operation.outsource_defect_qty is '累计外协不良数量';

alter table work_order_operation drop constraint if exists ck_wo_op_outsource_status;
alter table work_order_operation add constraint ck_wo_op_outsource_status
    check (outsource_status is null or outsource_status in ('PENDING_SEND', 'SENT', 'RECEIVED'));

create index if not exists idx_wo_op_outsource on work_order_operation (outsource_status);

-- ---------------------------------------------------------------- 2. 外协加工单
create table if not exists outsource_order (
    id bigint generated always as identity primary key,
    order_no varchar(32) not null unique,
    work_order_id bigint not null references work_order(id),
    operation_id bigint not null references work_order_operation(id),
    operation_seq int not null,
    supplier_id bigint not null references supplier(id),
    status varchar(16) not null default 'SENT',
    qty numeric(18, 4) not null,
    price numeric(12, 2) not null default 0,
    received_qty numeric(18, 4) not null default 0,
    defect_qty numeric(18, 4) not null default 0,
    amount numeric(18, 2) not null default 0,
    expected_at date,
    sent_at timestamptz not null default now(),
    received_at timestamptz,
    sender varchar(40),
    receiver varchar(40),
    remark varchar(200),
    factory_org_id bigint references sys_org(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint ck_os_status check (status in ('SENT', 'PARTIAL', 'RECEIVED')),
    constraint ck_os_qty check (qty > 0),
    constraint ck_os_received check (received_qty >= 0 and received_qty <= qty),
    constraint ck_os_defect check (defect_qty >= 0 and defect_qty <= received_qty)
);

comment on table outsource_order is '外协加工单：一次「发出去做」的动作，收回登记也记在这张单上';
comment on column outsource_order.status is 'SENT 已发出 / PARTIAL 部分收回 / RECEIVED 已收完；发错只能收回后走退料，不做作废';
comment on column outsource_order.qty is '本次外协的成品数量（件），不是发料数量';
comment on column outsource_order.price is '外协单价（不含料）；amount = (收回数 − 不良数) × 单价';
comment on column outsource_order.received_qty is '累计收回数量（含不良）';
comment on column outsource_order.defect_qty is '收回里的不良数量';

create index if not exists idx_os_work on outsource_order (work_order_id);
create index if not exists idx_os_supplier on outsource_order (supplier_id);
create index if not exists idx_os_status on outsource_order (status, expected_at);

create table if not exists outsource_order_item (
    id bigint generated always as identity primary key,
    order_id bigint not null references outsource_order(id) on delete cascade,
    material_id bigint not null references product(id),
    qty numeric(18, 4) not null,
    warehouse_id bigint references warehouse(id),
    batch_summary varchar(200),
    from_stock boolean not null default true,
    created_at timestamptz not null default now(),
    constraint ck_os_item_qty check (qty > 0)
);

comment on table outsource_order_item is '外协发出用料明细：从哪个仓、哪个批次、发了多少';
comment on column outsource_order_item.from_stock is
    'true=料从本厂仓发出（扣库存）；false=该工序早已领料，随件转出不重复扣库存';

create index if not exists idx_os_item_order on outsource_order_item (order_id);

-- ---------------------------------------------------------------- 3. 成本与读模型
alter table work_order add column if not exists outsource_cost numeric(18, 2) not null default 0;
comment on column work_order.outsource_cost is '外协加工费合计，与料工费并列，不再含在人工里';

-- 主表加了列，读模型要跟着扩（列只能往后加）
create or replace view rm_work_order as select * from work_order;
create or replace view rm_work_order_operation as select * from work_order_operation;
create or replace view rm_outsource_order as select * from outsource_order;
create or replace view rm_outsource_order_item as select * from outsource_order_item;

-- ---------------------------------------------------------------- 4. 单号与权限
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle)
values ('OS', '外协加工单', 'OS', 'yyyyMMdd', '', 4, 'DAY')
on conflict (biz_type) do nothing;

-- 外协发出要扣库存，所以和领料同一档（管理员 / 计划 / 仓库）；收回要出检验结论，给质检
insert into sys_role_permission (role_id, permission) values
(1, 'production:outsource'), (3, 'production:outsource'), (4, 'production:outsource'),
(1, 'production:outsource-receive'), (3, 'production:outsource-receive'), (5, 'production:outsource-receive')
on conflict do nothing;
