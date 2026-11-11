-- 为迁移前已存在的工艺路线补一条初始化记录，
-- 让产品页的「变更记录」能显示路线的初始形态，而不是空白。
insert into product_routing_change (product_id, action, old_summary, new_summary, remark, changed_by)
select r.product_id,
       'INIT',
       null,
       string_agg(p.name, '→' order by r.seq),
       '演示数据初始化',
       'system'
from product_routing r
         join process p on p.id = r.process_id
where not exists (select 1 from product_routing_change c where c.product_id = r.product_id)
group by r.product_id;
