-- 扫码检验：检验单要能落在具体工序上
alter table inspection add column if not exists operation_id bigint references work_order_operation(id);
alter table inspection add column if not exists operation_seq int;

create index if not exists idx_inspection_operation on inspection (operation_id);

-- 不良来源增加「工序检验」：扫码在工序上判不合格时，不良要能落到该工序，
-- 处置返修才能从这道工序起做，而不是整件重头来
alter table defect_record drop constraint if exists ck_defect_source;
alter table defect_record add constraint ck_defect_source
    check (source in ('REPORT', 'PROCESS_QC', 'FINAL_QC', 'INCOMING', 'RETURN'));

-- 历史检验单回填工序序号，列表展示用
update inspection i
set operation_seq = o.seq
from work_order_operation o
where i.operation_id = o.id and i.operation_seq is null;
