-- 条件分流：同一业务类型可以挂多条审批链，按单据变量命中其中一条。
--
-- 之前的限制是「一个业务类型只能有一条启用流程」，采购只能一刀切。
-- 现实是标准件、外协件、设备备件的审批链完全不同，所以把「命中哪条流程」交给规则，
-- 规则为空的那条作为兜底（catch-all）。

alter table sys_approval_flow add column if not exists match_rules jsonb;
alter table sys_approval_flow add column if not exists priority int not null default 100;
alter table sys_approval_flow add column if not exists match_note varchar(255);
comment on column sys_approval_flow.match_rules is '适用条件：JSON 数组，元素 {field, op, value}，多条为 AND；为空表示兜底';
comment on column sys_approval_flow.priority is '同业务类型内优先级，大的先匹配';

create index if not exists idx_approval_flow_match on sys_approval_flow (biz_type, priority desc)
    where enabled = true;

-- 命中留痕：这张单为什么走了这条链，出事能追
create table if not exists sys_approval_match_log (
    id bigint generated always as identity primary key,
    biz_type varchar(32) not null,
    biz_id bigint not null,
    biz_no varchar(64),
    flow_id bigint,
    flow_code varchar(64),
    variables jsonb,
    matched boolean not null default false,
    reason varchar(255),
    created_at timestamptz not null default now()
);
create index if not exists idx_approval_match_biz on sys_approval_match_log (biz_type, biz_id);

create or replace view rm_approval_match_log as select * from sys_approval_match_log;

-- 采购申请补「采购类别 / 是否紧急 / 预算科目」：分流规则最常用的三个字段
alter table purchase_requisition add column if not exists category varchar(32) not null default 'MATERIAL';
alter table purchase_requisition add column if not exists urgent boolean not null default false;
alter table purchase_requisition add column if not exists budget_no varchar(64);
comment on column purchase_requisition.category is 'MATERIAL 原辅料 / OUTSOURCE 外协 / SPARE 设备备件 / EQUIPMENT 设备 / SERVICE 服务';

create or replace view rm_purchase_requisition as select * from purchase_requisition;
