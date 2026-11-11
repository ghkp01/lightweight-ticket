-- 组织架构与工作中心：把「车间」变成真实实体，把产能从工序上移到工作中心

-- 1. 组织树：工厂 -> 车间 -> 班组；职能部门可挂工厂或车间
create table sys_org (
    id bigint generated always as identity primary key,
    parent_id bigint references sys_org(id),
    code varchar(32) not null unique,
    name varchar(64) not null,
    type varchar(16) not null,
    path varchar(255) not null default '/',
    level int not null default 1,
    sort int not null default 0,
    leader varchar(64),
    phone varchar(20),
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_org_type check (type in ('PLANT', 'WORKSHOP', 'TEAM', 'DEPT'))
);
create index idx_org_parent on sys_org(parent_id);
create index idx_org_path on sys_org(path);

-- 2. 工作中心：真正承载日产能的执行单元，挂在车间或班组下
create table work_center (
    id bigint generated always as identity primary key,
    code varchar(32) not null unique,
    name varchar(64) not null,
    org_id bigint references sys_org(id),
    type varchar(16) not null default 'MACHINE',
    daily_capacity_minutes int not null default 480,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamptz not null default now(),
    constraint ck_work_center_type check (type in ('MACHINE', 'HAND', 'OUTSOURCE')),
    constraint ck_work_center_capacity check (daily_capacity_minutes > 0)
);
create index idx_work_center_org on work_center(org_id);

-- 3. 工序默认在哪个工作中心执行
alter table process add column work_center_id bigint references work_center(id);
create index idx_process_work_center on process(work_center_id);

-- 4. 人员归属：组织 / 岗位 / 工号
alter table sys_user add column org_id bigint references sys_org(id);
alter table sys_user add column position varchar(32);
alter table sys_user add column employee_no varchar(32);
create unique index uk_sys_user_employee_no on sys_user(employee_no) where employee_no is not null;
create index idx_sys_user_org on sys_user(org_id);

-- 5. 数据范围新增「本组织及下级」
alter table sys_role drop constraint ck_role_scope;
alter table sys_role add constraint ck_role_scope
    check (data_scope in ('ALL', 'SELF', 'WAREHOUSE', 'ORG'));

-- 6. 演示组织树：一个工厂、两个车间、四个班组、四个职能部门
insert into sys_org (id, parent_id, code, name, type, path, level, sort, leader) overriding system value values
(1,  null, 'PLANT01', '智造工厂',   'PLANT',    '/1/',       1, 1, '张建国'),
(2,  1,    'WS01',    '一号车间',   'WORKSHOP', '/1/2/',     2, 1, '李国强'),
(3,  1,    'WS02',    '二号车间',   'WORKSHOP', '/1/3/',     2, 2, '王振华'),
(4,  2,    'T01',     '下料班组',   'TEAM',     '/1/2/4/',   3, 1, '赵磊'),
(5,  2,    'T02',     '机加班组',   'TEAM',     '/1/2/5/',   3, 2, '孙浩'),
(6,  2,    'T03',     '装配班组',   'TEAM',     '/1/2/6/',   3, 3, '周敏'),
(7,  3,    'T04',     '喷涂班组',   'TEAM',     '/1/3/7/',   3, 1, '吴涛'),
(8,  1,    'D01',     '计划部',     'DEPT',     '/1/8/',     2, 3, '陈静'),
(9,  1,    'D02',     '仓储部',     'DEPT',     '/1/9/',     2, 4, '刘洋'),
(10, 1,    'D03',     '质量部',     'DEPT',     '/1/10/',    2, 5, '郑霞'),
(11, 1,    'D04',     '销售部',     'DEPT',     '/1/11/',    2, 6, '黄鹏');

select setval(pg_get_serial_sequence('sys_org', 'id'), (select max(id) from sys_org));

-- 7. 工作中心：下料 / 机加 / 装配 / 喷涂 / 质检 / 包装
insert into work_center (id, code, name, org_id, type, daily_capacity_minutes, remark) overriding system value values
(1, 'WC-CUT',   '下料中心', 4,  'MACHINE', 480, '锯切机、激光切割'),
(2, 'WC-MACH',  '机加中心', 5,  'MACHINE', 360, 'CNC 铣削、钻孔、攻丝'),
(3, 'WC-ASSY',  '装配中心', 6,  'HAND',    480, '总装工位'),
(4, 'WC-SPRAY', '喷涂中心', 7,  'HAND',    360, '喷粉线'),
(5, 'WC-QC',    '质检中心', 10, 'HAND',    240, '尺寸与外观检验'),
(6, 'WC-PACK',  '包装中心', 6,  'HAND',    480, '贴标、装箱');

select setval(pg_get_serial_sequence('work_center', 'id'), (select max(id) from work_center));

-- 8. 工序绑定工作中心：产能以工作中心为准
update process p set work_center_id = wc.id
from work_center wc
where (p.category = 'CUTTING'    and wc.code = 'WC-CUT')
   or (p.category = 'MACHINING'  and wc.code = 'WC-MACH')
   or (p.category = 'BENDING'    and wc.code = 'WC-MACH')
   or (p.category = 'ASSEMBLY'   and wc.code = 'WC-ASSY')
   or (p.category = 'SPRAYING'   and wc.code = 'WC-SPRAY')
   or (p.category = 'INSPECTION' and wc.code = 'WC-QC')
   or (p.category = 'PACKING'    and wc.code = 'WC-PACK');

-- 9. 现有账号挂到组织上
update sys_user set org_id = 1,  position = '系统管理员', employee_no = 'E0001' where username = 'admin';
update sys_user set org_id = 11, position = '销售员',     employee_no = 'E0002' where username = 'sales';
update sys_user set org_id = 8,  position = '计划员',     employee_no = 'E0003' where username = 'planner';
update sys_user set org_id = 9,  position = '仓管员',     employee_no = 'E0004' where username = 'warehouse';
update sys_user set org_id = 10, position = '质检员',     employee_no = 'E0005' where username = 'qc';

-- 10. 两个一线操作工，便于在班组里演示派工与成员
insert into sys_user (username, password, real_name, role, phone, org_id, position, employee_no)
select 'worker1', password, '张伟', 'PLANNER', '13800000011', 4, '操作工', 'E0011'
from sys_user where username = 'admin'
on conflict (username) do nothing;
insert into sys_user (username, password, real_name, role, phone, org_id, position, employee_no)
select 'worker2', password, '李强', 'PLANNER', '13800000012', 6, '操作工', 'E0012'
from sys_user where username = 'admin'
on conflict (username) do nothing;

-- 11. 权限：组织架构菜单与操作权
insert into sys_role_permission (role_id, permission) values
(1, 'system_org'), (1, 'org:save'), (1, 'workcenter:save')
on conflict do nothing;

-- 12. 计划员可以看到组织架构（只读），便于排产时确认班组
insert into sys_role_permission (role_id, permission) values (3, 'system_org')
on conflict do nothing;
