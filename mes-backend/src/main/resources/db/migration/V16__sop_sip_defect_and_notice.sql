-- SOP / SIP 文档、工序检验模式、不良处置、退货返修回链、站内通知

-- 1. 产品文档：SOP 作业指导书、SIP 检验指导书、图纸、包装规范
create table product_document (
    id bigint generated always as identity primary key,
    product_id bigint not null references product(id) on delete cascade,
    process_id bigint references process(id),
    doc_type varchar(16) not null,
    title varchar(128) not null,
    version varchar(32) not null default 'V1.0',
    content text,
    file_url varchar(512),
    enabled boolean not null default true,
    created_by varchar(64),
    created_at timestamptz not null default now(),
    constraint ck_document_type check (doc_type in ('SOP', 'SIP', 'DRAWING', 'PACKING', 'OTHER'))
);
create index idx_document_product on product_document(product_id, doc_type);
create index idx_document_process on product_document(process_id);

-- 2. 工序检验模式：NONE 不检 / ATTACHED 工序内检验点 / ROUTING 独立检验工序
alter table process add column inspection_mode varchar(16) not null default 'NONE';
update process set inspection_mode = 'ROUTING' where need_inspection = true;
alter table process add constraint ck_process_inspection_mode
    check (inspection_mode in ('NONE', 'ATTACHED', 'ROUTING'));
alter table product_routing add column inspection_mode varchar(16);
alter table work_order_operation add column inspection_mode varchar(16);

-- 3. 不良记录与处置
create table defect_record (
    id bigint generated always as identity primary key,
    defect_no varchar(32) not null unique,
    work_order_id bigint references work_order(id),
    operation_id bigint references work_order_operation(id),
    process_id bigint references process(id),
    product_id bigint references product(id),
    qty numeric(18,4) not null,
    source varchar(16) not null,
    reason varchar(255),
    disposition varchar(16),
    status varchar(16) not null default 'PENDING',
    rework_work_order_id bigint references work_order(id),
    handled_by varchar(64),
    handled_at timestamptz,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_defect_source check (source in ('REPORT', 'FINAL_QC', 'INCOMING', 'RETURN')),
    constraint ck_defect_status check (status in ('PENDING', 'DISPOSED')),
    constraint ck_defect_disposition check (
        disposition is null or disposition in ('REWORK', 'CONCESSION', 'SCRAP', 'RETURN_SUPPLIER'))
);
create index idx_defect_status on defect_record(status, created_at desc);
create index idx_defect_work on defect_record(work_order_id);

-- 4. 退货与返修工单、检验单回链
alter table return_order add column rework_work_order_id bigint references work_order(id);
alter table return_order add column inspection_id bigint references inspection(id);

-- 5. 站内通知：按人推送，关键节点自动产生
create table sys_notice (
    id bigint generated always as identity primary key,
    receiver varchar(64) not null,
    category varchar(16) not null default 'INFO',
    title varchar(128) not null,
    content varchar(500),
    ref_type varchar(24),
    ref_no varchar(64),
    read_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_notice_category check (category in ('TASK', 'ALERT', 'INFO'))
);
create index idx_notice_receiver on sys_notice(receiver, read_at, created_at desc);

-- 6. 权限
insert into sys_role_permission (role_id, permission) values
(1, 'document:save'), (1, 'defect:handle')
on conflict do nothing;
insert into sys_role_permission (role_id, permission) values
(3, 'document:save'), (5, 'defect:handle')
on conflict do nothing;

-- 7. 演示文档：铝合金外壳的装配 SOP 与终检 SIP
insert into product_document (product_id, process_id, doc_type, title, version, content, created_by)
select p.id, pr.id, 'SOP', '铝合金外壳 下料作业指导书', 'V1.2',
       '1. 核对铝型材批次与材质单，确认 6061-T6；' || chr(10) ||
       '2. 按图号下料，长度公差 ±0.5mm，切口垂直度 ≤0.2mm；' || chr(10) ||
       '3. 首件自检合格后填写首件记录，再批量生产；' || chr(10) ||
       '4. 每 50 件抽检一次，超差立即停机并呼叫班组长。',
       'admin'
from product p join process pr on pr.code = 'CUT'
where p.code = 'P001'
  and not exists (select 1 from product_document d where d.product_id = p.id and d.doc_type = 'SOP');

insert into product_document (product_id, process_id, doc_type, title, version, content, created_by)
select p.id, pr.id, 'SIP', '铝合金外壳 终检检验指导书', 'V2.0',
       '1. 外观：无划伤、无毛刺、色差在色板范围内（目视 30cm）；' || chr(10) ||
       '2. 尺寸：长宽高按图纸全检，超差即判不合格；' || chr(10) ||
       '3. 装配：螺栓扭矩 8±1 N·m，密封圈无外露；' || chr(10) ||
       '4. 抽检比例：全检外观与装配，尺寸按 AQL 1.0 抽样。',
       'admin'
from product p join process pr on pr.code = 'QC'
where p.code = 'P001'
  and not exists (select 1 from product_document d where d.product_id = p.id and d.doc_type = 'SIP');

-- 8. 把工艺路线与工单工序的检验模式按现有 need_inspection 回填
update product_routing r set inspection_mode = p.inspection_mode
from process p where p.id = r.process_id;
update work_order_operation o set inspection_mode = p.inspection_mode
from process p where p.id = o.process_id;
