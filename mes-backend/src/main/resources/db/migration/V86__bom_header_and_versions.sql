-- BOM 标准化：BOM 头（版本 / 状态 / 生效日期 / 基础数量）+ 行明细
-- 一个物料可以维护多套 BOM，同一时刻只有一套 ACTIVE；工单与 MRP 按当前生效版本取料。

create table bom (
    id bigint generated always as identity primary key,
    bom_no varchar(32) not null unique,
    product_id bigint not null references product(id),
    version varchar(32) not null,
    status varchar(16) not null default 'DRAFT',
    base_qty numeric(18,4) not null default 1,
    effective_from date not null default current_date,
    effective_to date,
    remark varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_bom_status check (status in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    constraint ck_bom_base_qty check (base_qty > 0),
    constraint ck_bom_effective check (effective_to is null or effective_to >= effective_from)
);

create unique index uk_bom_product_version on bom (product_id, version);
-- 同一物料只能有一套生效 BOM，避免工单取料时出现两套口径
create unique index uk_bom_active on bom (product_id) where status = 'ACTIVE';
create index idx_bom_product on bom (product_id);

alter table bom_item add column bom_id bigint references bom(id) on delete cascade;

-- 历史用料清单每个物料补一套 V1.0 生效 BOM，保证升级后工单/MRP 口径不变
insert into bom (bom_no, product_id, version, status, base_qty, effective_from, remark)
select 'BOM' || lpad(p.id::text, 6, '0'), p.id, 'V1.0', 'ACTIVE', 1, date '2000-01-01', '系统升级自动生成'
from product p
where exists (select 1 from bom_item b where b.product_id = p.id);

update bom_item b
   set bom_id = m.id
  from bom m
 where m.product_id = b.product_id
   and b.bom_id is null;

delete from bom_item where bom_id is null;

-- rm_bom_item 是 select * 的旧读模型，依赖 product_id 列，必须先删掉
drop view if exists rm_bom_item;

alter table bom_item alter column bom_id set not null;
alter table bom_item drop constraint uk_bom;
alter table bom_item drop column product_id;
alter table bom_item add constraint uk_bom_line unique (bom_id, material_id);
create index idx_bom_item_bom on bom_item (bom_id);

-- 读模型保留 product_id 口径，报表与工作台查询不用改
create view rm_bom_item as
select i.id, h.product_id, i.bom_id, i.material_id, i.qty, i.operation_seq,
       i.substitute_group, i.is_key, i.remark, i.substitute_material_id,
       h.status as bom_status
from bom_item i
join bom h on h.id = i.bom_id;

comment on view rm_bom_item is '跨域只读模型：BOM 行（含所属物料、BOM 状态与版本头信息）';

-- BOM 编号沿用编码规则，新建时可留空自动生成
insert into sys_code_rule (biz_type, biz_name, prefix, date_pattern, separator, seq_length, reset_cycle)
values ('BOM', '物料清单', 'BOM', '', '', 4, 'NEVER')
on conflict (biz_type) do nothing;
