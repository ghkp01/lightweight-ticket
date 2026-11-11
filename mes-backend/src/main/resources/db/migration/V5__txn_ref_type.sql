-- 生产退料来源类型较长，扩展流水来源类型长度
alter table stock_txn alter column ref_type type varchar(32);
