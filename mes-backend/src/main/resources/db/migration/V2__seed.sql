-- 演示数据
insert into customer (id, code, name, contact, phone, address, level) overriding system value values
(1, 'C001', '华东精密制造有限公司', '王海涛', '13800001111', '上海市嘉定区工业园 88 号', 'A'),
(2, 'C002', '苏州恒达机电有限公司', '李敏', '13900002222', '苏州市吴中区木渎镇金枫路 12 号', 'A'),
(3, 'C003', '宁波海川实业有限公司', '陈志强', '13700003333', '宁波市北仑区大港工业城 6 号', 'B');

insert into product (id, code, name, spec, category, unit, price, cost, safety_stock, loss_rate) overriding system value values
(1, 'P001', '铝合金外壳', '180×120×45', 'FINISHED', '件', 268.0000, 180.0000, 50.0000, 0.0100),
(2, 'P002', '不锈钢支架', 'L 型 320mm', 'FINISHED', '件', 158.0000, 96.0000, 40.0000, 0.0100),
(3, 'P003', '铝合金型材', '6061-T6', 'RAW', 'kg', 45.0000, 32.0000, 300.0000, 0.0200),
(4, 'P004', '不锈钢板材', '304 2mm', 'RAW', 'kg', 62.0000, 40.0000, 200.0000, 0.0200),
(5, 'P005', '标准螺栓', 'M6×20', 'RAW', '个', 0.8000, 0.5000, 5000.0000, 0.0000),
(6, 'P006', '密封圈', '硅胶 φ60', 'RAW', '个', 2.5000, 1.2000, 1000.0000, 0.0000),
(7, 'P007', '外壳半成品', '喷涂后半成品', 'SEMI', '件', 120.0000, 80.0000, 30.0000, 0.0100),
(8, 'P008', '支架半成品', '折弯后半成品', 'SEMI', '件', 80.0000, 55.0000, 20.0000, 0.0100);

insert into bom_item (id, product_id, material_id, qty) overriding system value values
(1, 1, 3, 0.8000),
(2, 1, 5, 6.0000),
(3, 1, 6, 2.0000),
(4, 2, 4, 0.6000),
(5, 2, 5, 4.0000),
(6, 2, 6, 1.0000),
(7, 7, 3, 0.9000),
(8, 7, 5, 2.0000),
(9, 8, 4, 0.7000),
(10, 8, 5, 2.0000);

insert into warehouse (id, code, name, type) overriding system value values
(1, 'WH-RAW', '原料仓', 'RAW'),
(2, 'WH-FIN', '成品仓', 'FINISHED'),
(3, 'WH-SEMI', '半成品仓', 'GENERAL');

insert into inventory (id, product_id, warehouse_id, qty, locked_qty) overriding system value values
(1, 1, 2, 120.0000, 0),
(2, 2, 2, 60.0000, 0),
(3, 3, 1, 1200.0000, 0),
(4, 4, 1, 150.0000, 0),
(5, 5, 1, 20000.0000, 0),
(6, 6, 1, 5000.0000, 0),
(7, 7, 3, 60.0000, 0),
(8, 8, 3, 40.0000, 0);

insert into sales_order (id, order_no, customer_id, order_date, delivery_date, status, total_qty, total_amount, remark, created_by, created_at) overriding system value values
(1, 'SO' || to_char(current_date - 2, 'YYYYMMDD') || '0001', 1, current_date - 2, current_date + 5, 'CONFIRMED', 150, 34700.00, '十月批次订单', '销售-刘洋', now() - interval '2 day'),
(2, 'SO' || to_char(current_date - 5, 'YYYYMMDD') || '0002', 2, current_date - 5, current_date + 2, 'IN_PRODUCTION', 80, 21440.00, '加急，优先排产', '销售-刘洋', now() - interval '5 day'),
(3, 'SO' || to_char(current_date - 8, 'YYYYMMDD') || '0003', 3, current_date - 8, current_date - 1, 'READY_TO_SHIP', 40, 6320.00, '客户自提改为物流', '销售-周婷', now() - interval '8 day'),
(4, 'SO' || to_char(current_date - 12, 'YYYYMMDD') || '0004', 1, current_date - 12, current_date - 5, 'COMPLETED', 20, 5360.00, '样品单转正式单', '销售-刘洋', now() - interval '12 day'),
(5, 'SO' || to_char(current_date - 1, 'YYYYMMDD') || '0005', 2, current_date - 1, current_date + 7, 'DRAFT', 30, 8040.00, '待客户确认图纸', '销售-周婷', now() - interval '1 day');

insert into sales_order_item (id, order_id, product_id, qty, price, amount, shipped_qty) overriding system value values
(1, 1, 1, 100, 268.0000, 26800.00, 0),
(2, 1, 2, 50, 158.0000, 7900.00, 0),
(3, 2, 1, 80, 268.0000, 21440.00, 0),
(4, 3, 2, 40, 158.0000, 6320.00, 40),
(5, 4, 1, 20, 268.0000, 5360.00, 20),
(6, 5, 1, 30, 268.0000, 8040.00, 0);

insert into work_order (id, work_no, sales_order_id, sales_order_item_id, product_id, plan_qty, done_qty, qualified_qty, status, plan_start, plan_end, created_at) overriding system value values
(1, 'WO' || to_char(current_date - 4, 'YYYYMMDD') || '0001', 2, 3, 1, 80, 30, 30, 'IN_PROGRESS', current_date - 4, current_date + 3, now() - interval '4 day'),
(2, 'WO' || to_char(current_date - 7, 'YYYYMMDD') || '0002', 3, 4, 2, 40, 40, 40, 'FINISHED', current_date - 7, current_date - 2, now() - interval '7 day'),
(3, 'WO' || to_char(current_date - 11, 'YYYYMMDD') || '0003', 4, 5, 1, 20, 20, 20, 'FINISHED', current_date - 11, current_date - 6, now() - interval '11 day');

insert into work_order_operation (id, work_order_id, seq, name, plan_qty, done_qty, status) overriding system value values
(1, 1, 1, '下料', 80, 80, 'DONE'),
(2, 1, 2, '机加工', 80, 30, 'IN_PROGRESS'),
(3, 1, 3, '装配', 80, 0, 'PENDING'),
(4, 2, 1, '下料', 40, 40, 'DONE'),
(5, 2, 2, '折弯', 40, 40, 'DONE'),
(6, 2, 3, '装配', 40, 40, 'DONE'),
(7, 3, 1, '下料', 20, 20, 'DONE'),
(8, 3, 2, '机加工', 20, 20, 'DONE'),
(9, 3, 3, '装配', 20, 20, 'DONE');

insert into work_report (id, work_order_id, operation_id, qty, qualified_qty, worker, remark, reported_at) overriding system value values
(1, 1, 1, 80, 80, '张师傅', '首件确认合格', now() - interval '3 day'),
(2, 1, 2, 30, 30, '李师傅', '', now() - interval '1 day'),
(3, 2, 1, 40, 40, '张师傅', '', now() - interval '6 day'),
(4, 2, 2, 40, 39, '赵师傅', '1 件折弯角度超差', now() - interval '4 day'),
(5, 2, 3, 40, 40, '王师傅', '', now() - interval '2 day'),
(6, 3, 1, 20, 20, '张师傅', '', now() - interval '10 day'),
(7, 3, 2, 20, 20, '李师傅', '', now() - interval '9 day'),
(8, 3, 3, 20, 20, '王师傅', '', now() - interval '8 day');

insert into shipment (id, shipment_no, sales_order_id, customer_id, status, carrier, tracking_no, address, shipped_at, signed_at, created_at) overriding system value values
(1, 'SH' || to_char(current_date - 6, 'YYYYMMDD') || '0001', 4, 1, 'SIGNED', '顺丰速运', 'SF1234567890', '上海市嘉定区工业园 88 号', now() - interval '6 day', now() - interval '5 day', now() - interval '6 day'),
(2, 'SH' || to_char(current_date - 1, 'YYYYMMDD') || '0002', 3, 3, 'DRAFT', '德邦物流', null, '宁波市北仑区大港工业城 6 号', null, null, now() - interval '1 day');

insert into shipment_item (id, shipment_id, sales_order_item_id, product_id, qty) overriding system value values
(1, 1, 5, 1, 20),
(2, 2, 4, 2, 40);

insert into inspection (id, inspection_no, type, ref_type, ref_no, work_order_id, product_id, inspect_qty, defect_qty, result, inspector, inspected_at, remark, created_at) overriding system value values
(1, 'QC' || to_char(current_date - 6, 'YYYYMMDD') || '0001', 'FINAL', 'WORK_ORDER', 'WO' || to_char(current_date - 7, 'YYYYMMDD') || '0002', 2, 2, 40, 0, 'PASS', '周敏', now() - interval '6 day', '外观与尺寸全检', now() - interval '6 day'),
(2, 'QC' || to_char(current_date - 4, 'YYYYMMDD') || '0002', 'INCOMING', 'PURCHASE', 'PO' || to_char(current_date - 4, 'YYYYMMDD') || '0001', null, 4, 150, 0, 'PASS', '周敏', now() - interval '4 day', '板材来料抽检', now() - interval '4 day'),
(3, 'QC' || to_char(current_date - 1, 'YYYYMMDD') || '0003', 'PROCESS', 'WORK_ORDER', 'WO' || to_char(current_date - 4, 'YYYYMMDD') || '0001', 1, 1, 30, 2, 'FAIL', '周敏', now() - interval '1 day', '2 件孔位偏移，已隔离', now() - interval '1 day');

insert into return_order (id, return_no, sales_order_id, customer_id, status, reason, disposition, total_qty, processed_at, created_at) overriding system value values
(1, 'RT' || to_char(current_date - 1, 'YYYYMMDD') || '0001', 4, 1, 'PENDING', '运输过程外观划伤', 'RESTOCK', 2, null, now() - interval '1 day'),
(2, 'RT' || to_char(current_date - 13, 'YYYYMMDD') || '0002', 3, 3, 'PROCESSED', '尺寸偏差超差', 'SCRAP', 1, now() - interval '12 day', now() - interval '13 day');

insert into return_item (id, return_id, product_id, qty) overriding system value values
(1, 1, 1, 2),
(2, 2, 2, 1);

select setval(pg_get_serial_sequence('customer', 'id'), (select max(id) from customer));
select setval(pg_get_serial_sequence('product', 'id'), (select max(id) from product));
select setval(pg_get_serial_sequence('bom_item', 'id'), (select max(id) from bom_item));
select setval(pg_get_serial_sequence('warehouse', 'id'), (select max(id) from warehouse));
select setval(pg_get_serial_sequence('inventory', 'id'), (select max(id) from inventory));
select setval(pg_get_serial_sequence('sales_order', 'id'), (select max(id) from sales_order));
select setval(pg_get_serial_sequence('sales_order_item', 'id'), (select max(id) from sales_order_item));
select setval(pg_get_serial_sequence('work_order', 'id'), (select max(id) from work_order));
select setval(pg_get_serial_sequence('work_order_operation', 'id'), (select max(id) from work_order_operation));
select setval(pg_get_serial_sequence('work_report', 'id'), (select max(id) from work_report));
select setval(pg_get_serial_sequence('shipment', 'id'), (select max(id) from shipment));
select setval(pg_get_serial_sequence('shipment_item', 'id'), (select max(id) from shipment_item));
select setval(pg_get_serial_sequence('inspection', 'id'), (select max(id) from inspection));
select setval(pg_get_serial_sequence('return_order', 'id'), (select max(id) from return_order));
select setval(pg_get_serial_sequence('return_item', 'id'), (select max(id) from return_item));
