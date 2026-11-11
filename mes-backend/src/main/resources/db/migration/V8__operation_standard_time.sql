-- 智能排产：工序标准工时

alter table work_order_operation add column standard_minutes numeric(18,2) not null default 0;

update work_order_operation
set standard_minutes = plan_qty * case name
    when '下料' then 1.5
    when '机加工' then 3.0
    when '装配' then 2.0
    else 1.0
end;
