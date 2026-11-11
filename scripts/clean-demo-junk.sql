-- ============================================================================
-- 清理回归脚本留在演示库里的垃圾数据（安全区）
--
-- 只动「按脚本约定生成」的账号、它们的站内消息 / 操作日志 / 技能 / 视图，
-- 以及带 6 位时间戳后缀的测试库位，不碰任何业务单据。
--
-- 深度清理（E2E 仓库、E2E 组织，及其下的调拨单 / 采购单 / 审批实例）
-- 见 clean-demo-e2e-facilities.sql，那个会连带删业务单据，需要单独确认。
--
-- 执行前先备份：
--   pg_dump -Fc -f backup-before-clean.dump -h 127.0.0.1 -p 5433 -U postgres mes
-- 执行：
--   psql -h 127.0.0.1 -p 5433 -U postgres -d mes -f scripts/clean-demo-junk.sql
-- 脚本可重复执行，第二次跑什么都不剩，不会报错。
-- ============================================================================

begin;

-- 回归脚本生成的账号：testqc53896 / orguser629819 / sec740934 / f1e2e / wh2e2e …
create temp table junk_user on commit drop as
select id, username
from sys_user
where username ~ '^(testqc[0-9]+|orguser[0-9]+|sec[0-9]+|e2e[0-9]*|f1e2e|f2e2e|wh2e2e)$';

-- 回归脚本建的库位：A-742017 / B-742030 这种「字母-6 位时间戳」
create temp table junk_loc on commit drop as
select id
from warehouse_location
where code ~ '^[A-Za-z]+-[0-9]{6}$';

-- 1. 站内消息：先清掉发给垃圾账号的（演示库里光这一项就有一万多条）
delete from sys_notice where receiver in (select username from junk_user);

-- 2. 操作日志：垃圾账号留下的调用流水
delete from oper_log where operator in (select username from junk_user);

-- 3. 账号关联数据
delete from user_skill where user_id in (select id from junk_user);
delete from sys_user_view where user_id in (select id from junk_user);
delete from sys_approval_signature where approver in (select username from junk_user);
update work_order_operation set assigned_user_id = null where assigned_user_id in (select id from junk_user);

-- 4. 库位：先把挂在库位上的批次下架（数量不变，只是回到「未指定库位」）
update inventory_batch set location_id = null where location_id in (select id from junk_loc);
delete from stock_move
 where from_location_id in (select id from junk_loc)
    or to_location_id in (select id from junk_loc);
delete from warehouse_location where id in (select id from junk_loc);

-- 5. 最后删账号本身
delete from sys_user where id in (select id from junk_user);

commit;
