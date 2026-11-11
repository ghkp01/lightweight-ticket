-- 给三个真实仓库补一套示例库位。
-- 之前库位表里的数据全是回归脚本建的（A-742017 这种带时间戳的编码），清理后就没有库位可用了，
-- 入库时选不到库位，库位页也是一片空白。这里补一套正常的 A 区 / B 区货位作为起点，
-- 客户可以按自己的货架编码改成实际的。
insert into warehouse_location (warehouse_id, code, name, zone, enabled, remark)
select w.id, v.code, v.name, v.zone, true, '开箱初始化库位，可按实际货架编码修改'
from warehouse w
cross join (values
    ('A-01', 'A区 01 货位', 'A区'),
    ('A-02', 'A区 02 货位', 'A区'),
    ('B-01', 'B区 01 货位', 'B区'),
    ('B-02', 'B区 02 货位', 'B区')
) as v(code, name, zone)
where w.code in ('WH-RAW', 'WH-FIN', 'WH-SEMI')
on conflict (warehouse_id, code) do nothing;
