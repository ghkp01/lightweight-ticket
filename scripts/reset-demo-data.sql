-- 业务数据全部回到期初：主数据（产品/BOM/工艺路线/工序/客户/供应商/仓库/组织/账号）一律保留
truncate table work_report, work_order_unit_log, work_order_unit, work_order_operation,
    work_order_bom_change, work_order_bom, work_order_material_batch, work_order,
    production_issue_item, production_issue, production_return_item, production_return,
    outsource_order_item, outsource_order,
    andon_call, sales_order_item, sales_order, inspection, inspection_item_result,
    shipment_batch, shipment_item, shipment, return_item, return_order,
    inbound_order_item, inbound_order, outbound_order_item, outbound_order,
    transfer_order_item, transfer_order, stocktake_item, stocktake, stock_move, stock_txn,
    defect_record, sys_notice, attach_file, oper_log, sys_user_view,
    purchase_quote_item, purchase_quote, purchase_rfq_item, purchase_rfq,
    purchase_order_item, purchase_order, purchase_requisition_item, purchase_requisition,
    supplier_price, purchase_return_item, purchase_return,
    ap_refund, ap_payment, ap_invoice_match, ap_invoice_item, ap_invoice,
    ap_accrual_item, ap_accrual,
    performance_sheet, work_hour,
    device_check_result, device_check_item, device_check_task, device_fault,
    device_maintain_log, device_maintain_plan,
    sys_approval_signature, sys_approval_extra_task, sys_approval_task, sys_approval_log,
    sys_approval_instance, sys_domain_event, sys_job_log, sys_job_lock
    restart identity cascade;
delete from code_seq;
-- 清掉回归脚本产生的临时账号，保留演示账号与组织
-- e2e 里会建 secXXXXXX（登录安全）、wh2e2e / f2e2e / f1e2e（仓库与工厂数据范围）这类临时账号
delete from sys_user
where username like 'testqc%' or username like 'orguser%' or username like 'pwtest%'
   or username like '%e2e%' or username like 'sec______';
update sys_code_rule set prefix = biz_type;

-- 清掉回归脚本创建的临时工作中心与设备，保留演示中心与设备台账
delete from work_center_device where code like 'TD%';
delete from work_center where code like 'TWC%' or code like 'TWO%';

-- 人员技能回到演示口径：下料、装配与包装、终检三个工序启用持证管控
delete from user_skill;
insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR' from sys_user u, process p
where u.username = 'worker1' and p.code = 'CUT';
insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR' from sys_user u, process p
where u.username = 'worker2' and p.code in ('ASSY', 'PACK');
insert into user_skill (user_id, process_id, level)
select u.id, p.id, 'SENIOR' from sys_user u, process p
where u.username = 'qc' and p.code = 'QC';

-- 工序检验方式回到演示口径：终检为独立检验工序，其余工序不检验
-- （「工序内检验点」是可选能力，可在工序管理或 e2e-quality 脚本里切换验证）
update process set inspection_mode = case when code = 'QC' then 'ROUTING' else 'NONE' end,
                 need_inspection = code = 'QC';
update product_routing r set inspection_mode = p.inspection_mode,
                             need_inspection = p.inspection_mode <> 'NONE'
from process p where p.id = r.process_id;

-- 系统配置回到演示口径：页面水印默认开启
update sys_config set config_value = 'true', updated_by = null, updated_at = now()
where config_key = 'security.watermark.enabled';

-- 附件存储回到演示口径：本地目录，清掉回归脚本写入的 S3 临时配置
-- 注意：attach_file 清空后，data/attachments 下的历史文件需要手工清理
update sys_config set config_value = 'LOCAL', updated_by = null, updated_at = now()
where config_key = 'storage.provider';
update sys_config set config_value = '', updated_by = null, updated_at = now()
where config_key in ('storage.s3.endpoint', 'storage.s3.access-key', 'storage.s3.secret-key');

-- 期初库存与 stock_txn 一起重置，保证「结存 = 期初 + 流水」可对账
-- 批次库存与追溯关系一并清空，回填的期初库存统一挂到 INIT 批次
truncate table inventory_batch, work_order_material_batch, shipment_batch restart identity;
update inventory set qty = 0, locked_qty = 0;
update inventory i
set qty = v.qty
from (values (1, 2, 120), (2, 2, 60), (3, 1, 1200), (4, 1, 150),
             (5, 1, 20000), (6, 1, 5000), (7, 3, 60), (8, 3, 40))
     as v(pid, wid, qty)
where i.product_id = v.pid and i.warehouse_id = v.wid;

insert into inventory_batch (product_id, warehouse_id, batch_no, qty, source)
select product_id, warehouse_id, 'INIT', qty, 'ADJUST' from inventory where qty <> 0;
