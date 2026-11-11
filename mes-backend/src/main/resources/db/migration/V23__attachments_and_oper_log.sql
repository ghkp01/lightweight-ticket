-- 附件台账 + 操作日志 + 存储配置

-- 1. 附件台账：数据库只存元数据，文件本体落在本地目录或 S3，storage_type 记录当时写到了哪里
create table attach_file (
    id bigint generated always as identity primary key,
    file_no varchar(32) not null,
    original_name varchar(255) not null,
    ext varchar(16),
    content_type varchar(128),
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    storage_type varchar(16) not null,
    storage_key varchar(512) not null,
    bucket varchar(128),
    biz_type varchar(32),
    biz_id bigint,
    biz_no varchar(64),
    uploaded_by varchar(64),
    uploaded_at timestamptz not null default now(),
    deleted boolean not null default false,
    constraint ck_attach_storage check (storage_type in ('LOCAL', 'S3')),
    constraint ck_attach_size check (size_bytes > 0)
);
create unique index ux_attach_file_no on attach_file (file_no);
create index idx_attach_file_biz on attach_file (biz_type, biz_id);
create index idx_attach_file_created on attach_file (uploaded_at desc);
create index idx_attach_file_hash on attach_file (sha256);
comment on table attach_file is '文件台账：统一上传入口写入，记录存储位置、摘要与业务归属';
comment on column attach_file.storage_type is '写入时的存储类型：LOCAL 本机目录 / S3 对象存储；切换配置不影响老文件读取';
comment on column attach_file.storage_key is '本地为相对根目录的路径，S3 为对象 key';

-- 2. 操作日志：非查询类接口自动记录，业务侧可补充摘要与单号
create table oper_log (
    id bigint generated always as identity primary key,
    module varchar(32) not null,
    action varchar(24) not null,
    biz_type varchar(32),
    biz_id bigint,
    biz_no varchar(64),
    summary varchar(500) not null,
    detail text,
    operator varchar(64),
    operator_role varchar(32),
    ip varchar(64),
    user_agent varchar(255),
    http_method varchar(8),
    request_path varchar(255),
    success boolean not null default true,
    error_msg varchar(500),
    duration_ms int,
    created_at timestamptz not null default now()
);
create index idx_oper_log_created on oper_log (created_at desc);
create index idx_oper_log_module on oper_log (module);
create index idx_oper_log_operator on oper_log (operator);
create index idx_oper_log_biz on oper_log (biz_type, biz_no);
comment on table oper_log is '操作日志：谁在什么时候对哪张单做了什么，失败也记';
comment on column oper_log.detail is '补充信息，键值文本，便于排查';

-- 3. 系统配置支持下拉与密文
alter table sys_config add column if not exists options varchar(255);
alter table sys_config add column if not exists sensitive boolean not null default false;
alter table sys_config drop constraint if exists ck_config_type;
alter table sys_config add constraint ck_config_type
    check (value_type in ('TEXT', 'BOOLEAN', 'NUMBER', 'SELECT'));

insert into sys_config (config_key, config_value, value_type, title, group_name, remark, options, sensitive)
values
('storage.provider', 'LOCAL', 'SELECT', '附件存储方式', '附件',
 'LOCAL 存本机目录；S3 存兼容 S3 的对象存储（MinIO、云厂商对象存储的 S3 接口）。切换后只影响新上传，老文件仍从原位置读取',
 'LOCAL,S3', false),
('storage.local.root', 'data/attachments', 'TEXT', '本地存储目录', '附件',
 '相对路径以服务启动目录为基准，例如 data/attachments；下载统一走接口，不直接暴露磁盘目录', null, false),
('storage.s3.endpoint', '', 'TEXT', 'S3 服务地址', '附件',
 '如 http://127.0.0.1:9000 或 https://oss-cn-hangzhou.aliyuncs.com；不填无法使用 S3', null, false),
('storage.s3.region', 'us-east-1', 'TEXT', 'S3 区域', '附件',
 'MinIO 一般填 us-east-1；云厂商按实际区域填', null, false),
('storage.s3.bucket', 'mes-attachments', 'TEXT', 'S3 存储桶', '附件',
 '附件统一放在这个桶下，按 年/月/日 分目录', null, false),
('storage.s3.access-key', '', 'TEXT', 'S3 Access Key', '附件',
 '对象存储的访问密钥 ID；建议用只授权该桶读写的子账号', null, false),
('storage.s3.secret-key', '', 'TEXT', 'S3 Secret Key', '附件',
 '只写不读：保存后列表显示掩码，留空提交表示不修改', null, true),
('storage.s3.path-style', 'true', 'BOOLEAN', 'S3 路径风格', '附件',
 'MinIO 必须为 true（地址形如 endpoint/bucket/key）；云厂商一般也用 true', null, false),
('operlog.retention.days', '90', 'NUMBER', '操作日志保留天数', '审计',
 '用于说明与清理脚本参考，系统不会自动删除日志', null, false)
on conflict (config_key) do nothing;

-- 4. 权限：上传人人可用，删除与台账/日志归管理员；主管也可上传现场照片
insert into sys_role_permission (role_id, permission) values
(1, 'file:upload'), (3, 'file:upload'), (4, 'file:upload'), (5, 'file:upload'),
(1, 'file:delete'),
(1, 'system_file'),
(1, 'system_log')
on conflict do nothing;
