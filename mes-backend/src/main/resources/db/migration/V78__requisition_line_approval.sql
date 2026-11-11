-- 行级分流：一张采购申请里的不同明细，可以各走各的审批链。
--
-- 之前按单据头的类别走一条链，混合单（外协件 + 标准件）只能被最高的那条卡住。
-- 现在每行带自己的类别，提交时逐行命中链，再按命中的链分组提交，
-- 同一组行共用一个审批实例（一个审批人批一次覆盖几行），不必一行一个待办。

alter table purchase_requisition_item add column if not exists category varchar(32);
alter table purchase_requisition_item add column if not exists approval_status varchar(16) not null default 'PENDING';
alter table purchase_requisition_item add column if not exists flow_code varchar(64);
alter table purchase_requisition_item add column if not exists approval_instance_id bigint;

comment on column purchase_requisition_item.category is '行级采购类别，空表示跟随单据头';
comment on column purchase_requisition_item.approval_status is
    'PENDING 待审批 / APPROVED 已通过 / REJECTED 被驳回 / SKIPPED 未命中任何链（视为无需审批）';

create index if not exists idx_req_item_approval on purchase_requisition_item (approval_instance_id);

create or replace view rm_purchase_requisition_item as select * from purchase_requisition_item;
