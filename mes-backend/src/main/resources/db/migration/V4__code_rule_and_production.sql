-- 自定义编码规则 + 生产闭环

alter table code_seq alter column prefix type varchar(64);

create table sys_code_rule (
    id bigint generated always as identity primary key,
    biz_type varchar(16) not null unique,
    biz_name varchar(64) not null,
    prefix varchar(16) not null,
    date_pattern varchar(16) not null default 'yyyyMMdd',
    separator varchar(4) not null default '',
    seq_length int not null default 4,
    reset_cycle varchar(8) not null default 'DAY',
    enabled boolean not null default true,
    updated_at timestamptz not null default now(),
    constraint ck_code_reset check (reset_cycle in ('DAY','MONTH','YEAR','NEVER')),
    constraint ck_code_length check (seq_length between 3 and 8)
);

insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle) values
('SO', '销售订单', 'SO', 'yyyyMMdd', '', 4, 'DAY'),
('WO', '生产工单', 'WO', 'yyyyMMdd', '', 4, 'DAY'),
('SH', '发货单', 'SH', 'yyyyMMdd', '', 4, 'DAY'),
('QC', '检验单', 'QC', 'yyyyMMdd', '', 4, 'DAY'),
('RT', '退货单', 'RT', 'yyyyMMdd', '', 4, 'DAY'),
('IN', '入库单', 'IN', 'yyyyMMdd', '', 4, 'DAY'),
('OUT', '出库单', 'OUT', 'yyyyMMdd', '', 4, 'DAY'),
('TR', '调拨单', 'TR', 'yyyyMMdd', '', 4, 'DAY'),
('ST', '盘点单', 'ST', 'yyyyMMdd', '', 4, 'DAY'),
('TX', '库存流水', 'TX', 'yyyyMMdd', '', 6, 'DAY');

alter table work_order add column work_type varchar(16) not null default 'NORMAL';
alter table work_order add column parent_work_order_id bigint references work_order(id);
alter table work_order_operation add column assigned_worker varchar(64);
alter table work_report add column defect_qty numeric(18,4) not null default 0;

create index idx_wo_parent on work_order(parent_work_order_id);

insert into sys_role_permission (role_id, permission)
select 1, 'system_code'
where not exists (select 1 from sys_role_permission where role_id = 1 and permission = 'system_code');
