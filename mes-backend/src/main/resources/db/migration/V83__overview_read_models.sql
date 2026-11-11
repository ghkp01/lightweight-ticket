-- 菜单分组工作台的只读模型：报表模块聚合各域指标时只读 rm_*，不直接碰业务表。
-- 补齐原先缺少的读模型（BOM、工艺路线、不良、出入库、账号/角色/任务日志）。

create or replace view rm_bom_item as select * from bom_item;
create or replace view rm_product_routing as select * from product_routing;
create or replace view rm_defect as select * from defect_record;
create or replace view rm_inbound_order as select * from inbound_order;
create or replace view rm_outbound_order as select * from outbound_order;
create or replace view rm_purchase_requisition as select * from purchase_requisition;
create or replace view rm_sys_user as select * from sys_user;
create or replace view rm_sys_role as select * from sys_role;
create or replace view rm_sys_job_log as select * from sys_job_log;

comment on view rm_bom_item is '跨域只读模型：基础数据覆盖率与工作台指标只读';
comment on view rm_product_routing is '跨域只读模型：工艺路线覆盖率与缺路线预警只读';
comment on view rm_defect is '跨域只读模型：质量工作台不良预警只读';
comment on view rm_inbound_order is '跨域只读模型：仓储工作台待入库统计只读';
comment on view rm_outbound_order is '跨域只读模型：仓储工作台待出库统计只读';
comment on view rm_purchase_requisition is '跨域只读模型：采购工作台待处理申请只读';
comment on view rm_sys_user is '跨域只读模型：系统工作台账号指标只读';
comment on view rm_sys_role is '跨域只读模型：系统工作台角色指标只读';
comment on view rm_sys_job_log is '跨域只读模型：系统工作台任务失败预警只读';
