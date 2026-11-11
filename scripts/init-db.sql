-- 在已有 PostgreSQL 实例上初始化 MES 数据库（用超级用户执行）
-- psql -U postgres -f scripts/init-db.sql

create role mes login password 'mes123';
create database mes owner mes encoding 'UTF8';
