-- 表格视图：列显示 / 冻结 / 列宽与默认查询条件按用户保存，跨设备复用

create table if not exists sys_user_view (
    id bigint generated always as identity primary key,
    user_id bigint not null references sys_user(id) on delete cascade,
    page_key varchar(64) not null,
    name varchar(40) not null,
    view_json jsonb not null default '{}'::jsonb,
    is_default boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_user_view_name unique (user_id, page_key, name)
);
comment on table sys_user_view is '用户表格视图：每个用户在每个页面可保存多套列设置与默认筛选';
comment on column sys_user_view.view_json is '{"columns":[{key,visible,width,fixed}],"filters":{},"pageSize":20}';

create index if not exists idx_user_view_page on sys_user_view (user_id, page_key);

-- 同一用户同一页面只允许一个默认视图
create unique index if not exists uk_user_view_default
    on sys_user_view (user_id, page_key) where is_default;
