-- 老报价单回填：V98 之前创建的单据 net/tax 是默认 0，金额三件套对不上
-- 只处理尚未拆分的行，已拆分的不会被重复计算
update sales_quote
set net_amount = case when price_includes_tax
        then round(total_amount / (1 + tax_rate), 2)
        else total_amount end,
    tax_amount = case when price_includes_tax
        then total_amount - round(total_amount / (1 + tax_rate), 2)
        else round(total_amount * tax_rate, 2) end,
    total_amount = case when price_includes_tax
        then total_amount
        else total_amount + round(total_amount * tax_rate, 2) end
where net_amount = 0 and tax_amount = 0 and total_amount <> 0;
