-- 审批开关：按业务单据（对应一个菜单）决定「走审批流 / 只走金额阈值 / 免审批」。
--
-- 之前只有「流程配不配」这一个维度：没配流程就静默跳过，配了就必须走，
-- 而且一个业务类型只能有一条流程。业务上要的是「我想配就配，不想配就关掉」，
-- 关掉之后单据要能照常提交，所以把强弱程度拆成三档。

create table if not exists sys_approval_biz (
    biz_type varchar(32) primary key,
    name varchar(64) not null,
    menu_path varchar(128),
    -- NONE 免审批（提交即生效）/ THRESHOLD 只按金额阈值分级 / FLOW 走配置的审批流
    mode varchar(16) not null default 'THRESHOLD',
    remark varchar(255),
    updated_by varchar(64),
    updated_at timestamptz not null default now(),
    constraint ck_approval_biz_mode check (mode in ('NONE', 'THRESHOLD', 'FLOW'))
);
comment on table sys_approval_biz is '业务审批开关：三档强度，没登记的业务类型按 FLOW 处理以兼容老行为';
comment on column sys_approval_biz.mode is 'NONE 免审批 / THRESHOLD 金额阈值 / FLOW 审批流';

create or replace view rm_approval_biz as select * from sys_approval_biz;

-- 存量两种业务保持走流程；采购申请保持原来的金额阈值，不动既有行为
insert into sys_approval_biz (biz_type, name, menu_path, mode, remark) values
('REQUISITION', '采购申请', '/purchase/requisitions', 'THRESHOLD', '默认按金额阈值分级，需要多级审批时切到审批流'),
('TRANSFER', '跨厂调拨', '/warehouse/transfers', 'FLOW', '调拨涉及两厂库存，默认走审批流'),
('PERFORMANCE', '绩效核算', '/reports/performance', 'FLOW', '绩效结果影响工资，默认走审批流')
on conflict (biz_type) do nothing;

-- 采购申请接进审批后要记住流程实例，审批中心的结果靠它回写单据
alter table purchase_requisition add column if not exists approval_instance_id bigint;

create or replace view rm_purchase_requisition as select * from purchase_requisition;
