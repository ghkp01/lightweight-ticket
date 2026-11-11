-- 调拨状态扩展：跨厂调拨要经过 待审批 → 已批准 / 已驳回 三个状态
alter table transfer_order drop constraint if exists ck_transfer_status;
alter table transfer_order add constraint ck_transfer_status
    check (status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'COMPLETED', 'CANCELLED'));
