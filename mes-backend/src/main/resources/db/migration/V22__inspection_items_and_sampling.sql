-- 检验项：把 SIP 从文档变成可执行的检验清单
create table inspection_item (
    id bigint generated always as identity primary key,
    product_id bigint references product(id),
    process_id bigint references process(id),
    item_code varchar(32),
    item_name varchar(128) not null,
    standard varchar(255),
    method varchar(128),
    value_type varchar(16) not null default 'COUNT',
    lower_limit numeric(18,4),
    upper_limit numeric(18,4),
    unit varchar(16),
    required boolean not null default true,
    sort_no int not null default 0,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    constraint ck_item_value_type check (value_type in ('COUNT', 'MEASURE', 'VISUAL')),
    constraint ck_item_scope check (product_id is not null or process_id is not null)
);

create index idx_inspection_item_process on inspection_item (process_id);
create index idx_inspection_item_product on inspection_item (product_id);

-- 检验单增加抽样信息：批量、抽样数、AQL 与接收 / 拒收数
alter table inspection add column if not exists sample_plan varchar(16) not null default 'FULL';
alter table inspection add column if not exists sample_qty numeric(18,4);
alter table inspection add column if not exists aql numeric(6,3);
alter table inspection add column if not exists accept_qty int;
alter table inspection add column if not exists reject_qty int;

-- 检验项实测结果
create table inspection_item_result (
    id bigint generated always as identity primary key,
    inspection_id bigint not null references inspection(id) on delete cascade,
    item_id bigint references inspection_item(id),
    item_name varchar(128) not null,
    standard varchar(255),
    sample_qty numeric(18,4),
    defect_qty numeric(18,4) not null default 0,
    measured varchar(64),
    result varchar(8) not null,
    remark varchar(255),
    sort_no int not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_item_result check (result in ('PASS', 'FAIL'))
);

create index idx_item_result_inspection on inspection_item_result (inspection_id);
create index idx_item_result_item on inspection_item_result (item_id);

-- 演示检验项：下料工序 2 项、终检工序 3 项
insert into inspection_item (process_id, item_code, item_name, standard, method, value_type,
                             lower_limit, upper_limit, unit, sort_no)
select id, 'CUT-LEN', '下料长度', '180±0.5 mm', '游标卡尺', 'MEASURE', 179.5, 180.5, 'mm', 1
from process where code = 'CUT';

insert into inspection_item (process_id, item_code, item_name, standard, method, value_type, sort_no)
select id, 'CUT-BURR', '切口毛刺', '无可见毛刺、飞边', '目视', 'VISUAL', 2
from process where code = 'CUT';

insert into inspection_item (process_id, item_code, item_name, standard, method, value_type, sort_no)
select id, 'QC-APPEAR', '外观', '无划伤、无变形、无油污', '目视', 'VISUAL', 1
from process where code = 'QC';

insert into inspection_item (process_id, item_code, item_name, standard, method, value_type,
                             lower_limit, upper_limit, unit, sort_no)
select id, 'QC-LEN', '外形尺寸', '180×120×45 ±0.5 mm', '卡尺 / 卷尺', 'MEASURE', 179.5, 180.5, 'mm', 2
from process where code = 'QC';

insert into inspection_item (process_id, item_code, item_name, standard, method, value_type, sort_no)
select id, 'QC-FUNC', '功能测试', '通电运行 3 分钟无异常', '通电测试台', 'VISUAL', 3
from process where code = 'QC';

-- 检验项维护权限：管理员与质检
insert into sys_role_permission (role_id, permission) values
(1, 'quality:item'), (5, 'quality:item')
on conflict do nothing;
