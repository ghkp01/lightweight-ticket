-- 预收账款：收款未核销部分先挂预收，后续再核销到发票

insert into fin_account (code, name, type, role) values
    ('2203', '预收账款', 'LIABILITY', 'AR_ADVANCE')
on conflict (code) do nothing;

-- 标记这张收款单之后是否又做过预收核销：做过就不允许直接取消，避免凭证重复红冲
alter table ar_receipt add column if not exists applied_later boolean not null default false;
comment on column ar_receipt.applied_later is '是否发生过后续预收核销；为 true 时不允许直接取消收款单';
