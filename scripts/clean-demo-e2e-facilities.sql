-- ============================================================================
-- 深度清理：E2E 仓库 / E2E 组织，以及挂在上面的业务单据
--
-- 这些数据全部由回归脚本生成（仓库编码 W1E2E / W2E2E，组织编码 P2E2E），
-- 包括跨厂调拨单与调拨审批实例、E2E 仓库下的采购单 / 入库单 / 库存 / 流水，
-- 以及 P2E2E 工厂名下的草稿采购申请、暂估与发票（已核对：申请全是 DRAFT，
-- 发票 paid_amount 全为 0，没有付款也没有退款记录）。
--
-- ⚠️ 和 clean-demo-junk.sql 不同，这个脚本会删业务单据，不可撤销。
--    执行前必须备份：
--      pg_dump -Fc -f backup.dump -h 127.0.0.1 -p 5433 -U postgres mes
--    恢复：
--      pg_restore -c -h 127.0.0.1 -p 5433 -U postgres -d mes backup.dump
--
-- 执行：
--   psql -h 127.0.0.1 -p 5433 -U postgres -d mes -f scripts/clean-demo-e2e-facilities.sql
-- 脚本可重复执行（第二次找不到数据就什么也不删）。
-- ============================================================================

begin;

create temp table junk_wh on commit drop as
select id from warehouse where code ~ 'E2E$';

create temp table junk_org on commit drop as
select id from sys_org where code ~ 'E2E$';

create temp table junk_po on commit drop as
select id from purchase_order where warehouse_id in (select id from junk_wh);

create temp table junk_in on commit drop as
select id from inbound_order where warehouse_id in (select id from junk_wh);

create temp table junk_tr on commit drop as
select id from transfer_order
 where from_warehouse_id in (select id from junk_wh)
    or to_warehouse_id in (select id from junk_wh);

create temp table junk_inst on commit drop as
select id from sys_approval_instance
 where biz_type = 'TRANSFER' and biz_id in (select id from junk_tr);

-- 1. 审批：先子表后主表，最后松开调拨单上的外键
--    signature / extra_task / log 的 instance_id 是 varchar，要显式转成文本比较
delete from sys_approval_signature  where instance_id in (select id::text from junk_inst);
delete from sys_approval_extra_task where instance_id in (select id::text from junk_inst);
delete from sys_approval_log        where instance_id in (select id::text from junk_inst);
delete from sys_approval_task       where instance_id in (select id from junk_inst);
update transfer_order set approval_instance_id = null where id in (select id from junk_tr);
delete from sys_approval_instance   where id in (select id from junk_inst);

-- 2. 发票：退款 / 付款 -> 三单匹配 -> 发票明细 -> 发票
--    范围 = E2E 仓库采购单开的票 + P2E2E 工厂名下的票
create temp table junk_inv on commit drop as
select id from ap_invoice
 where purchase_order_id in (select id from junk_po)
    or factory_org_id in (select id from junk_org);

delete from ap_refund  where invoice_id in (select id from junk_inv);
delete from ap_payment where invoice_id in (select id from junk_inv);
update purchase_return set red_invoice_id = null where red_invoice_id in (select id from junk_inv);
update ap_invoice set related_invoice_id = null where related_invoice_id in (select id from junk_inv);

create temp table junk_inv_item on commit drop as
select id from ap_invoice_item where invoice_id in (select id from junk_inv);

delete from ap_invoice_match
 where invoice_id in (select id from junk_inv)
    or invoice_item_id in (select id from junk_inv_item);
delete from ap_invoice_item where id in (select id from junk_inv_item);
delete from ap_invoice where id in (select id from junk_inv);

-- 3. 暂估：暂估明细 -> 暂估（范围同上）
create temp table junk_acc on commit drop as
select id from ap_accrual
 where purchase_order_id in (select id from junk_po)
    or inbound_order_id in (select id from junk_in)
    or factory_org_id in (select id from junk_org);

create temp table junk_acc_item on commit drop as
select id from ap_accrual_item where accrual_id in (select id from junk_acc);

delete from ap_invoice_match where accrual_item_id in (select id from junk_acc_item);
delete from ap_accrual_item where id in (select id from junk_acc_item);
delete from ap_accrual where id in (select id from junk_acc);

-- 4. 采购申请（P2E2E 工厂名下的草稿申请）
create temp table junk_req on commit drop as
select id from purchase_requisition where factory_org_id in (select id from junk_org);

delete from purchase_requisition_item where req_id in (select id from junk_req);
delete from purchase_requisition where id in (select id from junk_req);

-- 5. 入库单与采购单（先子表）
delete from inbound_order_item where inbound_id in (select id from junk_in);
delete from inbound_order where id in (select id from junk_in);
delete from purchase_order_item where order_id in (select id from junk_po);
delete from purchase_order where id in (select id from junk_po);

-- 6. 调拨单
delete from transfer_order_item where transfer_id in (select id from junk_tr);
delete from transfer_order where id in (select id from junk_tr);

-- 7. 库存与流水
delete from stock_txn        where warehouse_id in (select id from junk_wh);
delete from inventory_batch  where warehouse_id in (select id from junk_wh);
delete from inventory        where warehouse_id in (select id from junk_wh);
delete from stock_move       where warehouse_id in (select id from junk_wh);
delete from stocktake        where warehouse_id in (select id from junk_wh);

-- 8. 仓库与组织
delete from warehouse where id in (select id from junk_wh);
delete from sys_org   where id in (select id from junk_org);

commit;
