-- 采购申请单号流水：V25 的演示数据占用了 PR + 当天 + 0001，这里把流水对齐，避免新建申请撞号
insert into code_seq (prefix, seq_date, seq)
values ('PR', current_date, 1)
on conflict (prefix, seq_date) do update set seq = greatest(code_seq.seq, 1);
