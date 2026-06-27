ALTER TABLE zephyr_mcp_tools ADD COLUMN IF NOT EXISTS parameters_json text;

ALTER TABLE zephyr_skill_configs ADD COLUMN IF NOT EXISTS scope varchar(16) DEFAULT 'user';

delete from h_sm_info where app='zephyr';
insert into h_sm_info(app,info_content) values('zephyr','{"title":"Zephyr智能体"}');

alter table zephyr_model_configs add column if not exists max_context_tokens bigint;
alter table zephyr_model_configs add column if not exists params text;

alter table zephyr_conversations add column if not exists workspace_id varchar(64);

delete from h_menus where app='zephyr' and name in ('agents','zephyr_api');
insert into h_menus(app,name,menu_desc,url,parent_key,order_index,menu_level,icon_name,created_at) values('zephyr','agents','智能体','/agents','-',0,1,'HAProxyIcon',1735800456);
insert into h_menus(app,name,menu_desc,url,parent_key,order_index,menu_level,icon_name,created_at) values('zephyr','zephyr_api','Zephyr智能体','http://localhost:30733/zephyr/zephyr-ui/index.html#/chat','agents',1,2,'HAProxyIcon',1735800456);
insert into h_icons(name,icon_desc,icon_svg,icon_size,update_at) values('agent',' 智能体','<svg t="1781071343390" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="5096" width="20" height="20"><path d="M850.3 512.2c3.5-4.3 6.8-9.1 10-13.7 57.3-80.7 71.5-155.3 39.9-210.1-31.5-54.8-103.2-79.9-201.8-70.9-5.7 0.5-11.5 1.2-17.3 1.9-2.3-5.4-4.6-10.8-6.9-16C632.9 113.5 575.3 64 512 64s-120.8 49.7-162 139.6c-2.4 5.1-4.6 10.4-6.9 15.7-5.7-0.6-11.4-1.4-17.3-1.9-98.5-9.2-170.3 15.9-201.8 70.7s-17.3 129.4 39.8 210.1c3.2 4.3 6.7 9.1 10 13.6-3.5 4.3-6.8 9.2-10 13.6-57.3 80.7-71.6 155.4-39.9 210.2 27.4 47.6 84.8 72.7 163.6 72.7 12.2 0 25-0.6 38.1-1.8 5.7-0.5 11.4-1.2 17.3-1.9 2.2 5.4 4.6 10.7 6.9 16C391 910.5 448.6 960 511.9 960S632.6 910.6 674 820.6c2.4-5.1 4.6-10.5 6.9-15.7 18.4 2.5 37 3.7 55.6 3.8 78.7 0 135.9-25.3 163.3-72.5 31.6-54.8 17.6-129.4-39.7-210.1-3.2-4.7-6.7-9.3-10.2-13.9h0.4zM320.6 749.5c-73.9 6.8-127.5-8.7-147.2-42.5-19.6-33.8-6.1-87.9 37-148.4l1.5-2.1c26.9 28.4 56 54.6 87.1 78.4 5.1 38.7 13.2 76.9 24.3 114.3l-2.7 0.3z m-28.5-194.7C277 541.2 262.6 527 248.8 512c13.2-14.4 27.6-28.7 43.3-42.8-0.6 14.2-1 28.4-1 42.9s0.4 28.5 1 42.7z m7.2-165.8c-31.1 23.8-60.2 50-87.1 78.3-0.5-0.7-1.1-1.4-1.5-2.1-43-60.6-56.3-114.7-37-148.5 16.5-28.5 57.1-44 113.7-44 12.1 0 24.1 0.7 36.1 1.9-11 37.5-19.1 75.7-24.2 114.4zM659 342.6c-23.9-15.3-48.4-29.4-73.6-42.5 19.8-6.4 39.3-11.7 58.2-16 5.9 18.4 11 37.8 15.4 58.5zM512.3 121.3c38.7 0 79.7 40.1 111 108.9-37.8 9.1-74.9 21.1-110.8 36-36-14.9-73.1-27-111-36.2 31-68.7 72-108.6 110.8-108.7zM380.8 284c19 4.2 38.5 9.6 58.4 16.1-25.2 13.1-49.7 27.2-73.6 42.5 4.1-19.8 9.3-39.3 15.4-58.5h-0.2z m-15.4 397.4c23.8 15.3 48.3 29.4 73.6 42.5-19.7 6.4-39.2 11.8-58.2 16-6.2-19.2-11.3-38.7-15.4-58.5z m146.8 221.4c-39 0-79.7-40.1-111-108.9 37.8-9.1 74.8-21.1 110.8-36 36 14.9 73.1 27 111.1 36.1-31.3 68.7-72.3 108.9-110.9 108.8z m131.4-162.7c-19.7-4.4-39.2-9.8-58.4-16.1 25.2-13.1 49.8-27.2 73.8-42.5-4.3 20.8-9.5 40.3-15.4 58.6z m27.6-136c-24.9 18-50.7 34.7-77.3 50-26.6 15.3-53.9 29.3-82 41.7-56-25-109.3-55.8-158.9-91.9-3.2-30.5-4.8-61.2-4.8-91.8 0-30.7 1.6-61.4 4.8-92 49.7-36 103.1-66.7 159.2-91.7 56.1 24.9 109.3 55.7 158.9 91.9 6.5 61.1 6.5 122.7 0.1 183.8z m32.5-329.5c11.5-1.1 22.5-1.6 33-1.6 56.8 0 97.6 15.5 114 44 19.5 33.9 6 87.9-37 148.4l-1.5 2.1c-26.9-28.4-56-54.6-87.1-78.4-5.1-38.7-13.2-76.9-24.3-114.3l2.9-0.2z m28.6 194.7c15.5 14.1 30.3 28.3 43.3 42.8-13.3 14.4-27.7 28.7-43.3 42.8 0.6-14.2 1-28.5 1-42.9-0.1-14.4-0.4-28.6-1-42.7z m118.2 238.1c-19.5 33.8-73.1 49.2-147.2 42.2l-2.8-0.2c11.1-37.4 19.2-75.6 24.2-114.3 31.1-23.8 60.2-50 87.1-78.3 0.5 0.7 1.1 1.4 1.5 2.1 43 60.6 56.3 114.7 36.9 148.5h0.3z" fill="#1296db" p-id="5097"></path></svg>',20,1735800456);

ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS model_type varchar(16) DEFAULT 'llm';
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS dimensions int DEFAULT NULL;

-- 在线编写文档功能：增加 content 和 source_type 字段
alter table if exists zephyr_knowledge_doc add column if not exists content text;
alter table if exists zephyr_knowledge_doc add column if not exists source_type varchar(16) default 'upload';
alter table if exists zephyr_knowledge_doc add column if not exists updated_at bigint;

ALTER TABLE zephyr_knowledge_base ADD COLUMN IF NOT EXISTS scope varchar(16) DEFAULT 'user';

ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS scope VARCHAR(16) DEFAULT 'user';

ALTER TABLE zephyr_knowledge_base ADD COLUMN IF NOT EXISTS graph_enabled SMALLINT DEFAULT 0;
ALTER TABLE zephyr_knowledge_doc ADD COLUMN IF NOT EXISTS graph_status varchar(16);

alter table if exists zephyr_workspaces add column if not exists is_system smallint default 0;
-- ============================================================
-- 安全规则种子数据：从 application.yml 迁移到 zephyr_security_rules
-- 对所有数据库类型兼容（H2 / PostgreSQL / MySQL）
-- ============================================================

INSERT INTO zephyr_security_rules (id, rule_type, rule_value, description, enabled, created_at)
    SELECT 'seed_shell_001', 'SHELL_ALLOWED', 'rm', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_002', 'SHELL_ALLOWED', 'python3', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_003', 'SHELL_ALLOWED', 'python', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_004', 'SHELL_ALLOWED', 'node', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_005', 'SHELL_ALLOWED', 'ruby', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_006', 'SHELL_ALLOWED', 'perl', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_007', 'SHELL_ALLOWED', 'php', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_008', 'SHELL_ALLOWED', 'lua', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_009', 'SHELL_ALLOWED', 'deno', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_010', 'SHELL_ALLOWED', 'bun', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_011', 'SHELL_ALLOWED', 'npm', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_012', 'SHELL_ALLOWED', 'npx', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_013', 'SHELL_ALLOWED', 'yarn', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_014', 'SHELL_ALLOWED', 'pnpm', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_015', 'SHELL_ALLOWED', 'pip', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_016', 'SHELL_ALLOWED', 'pip3', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_017', 'SHELL_ALLOWED', 'gem', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_018', 'SHELL_ALLOWED', 'composer', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_019', 'SHELL_ALLOWED', 'cargo', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_020', 'SHELL_ALLOWED', 'go', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_021', 'SHELL_ALLOWED', 'git', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_022', 'SHELL_ALLOWED', 'hg', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_023', 'SHELL_ALLOWED', 'javac', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_024', 'SHELL_ALLOWED', 'java', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_025', 'SHELL_ALLOWED', 'mvn', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_026', 'SHELL_ALLOWED', 'gradle', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_027', 'SHELL_ALLOWED', 'make', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_028', 'SHELL_ALLOWED', 'cmake', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_029', 'SHELL_ALLOWED', 'gcc', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_030', 'SHELL_ALLOWED', 'g++', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_031', 'SHELL_ALLOWED', 'clang', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_032', 'SHELL_ALLOWED', 'clang++', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_033', 'SHELL_ALLOWED', 'rustc', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_034', 'SHELL_ALLOWED', 'dotnet', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_035', 'SHELL_ALLOWED', 'ls', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_036', 'SHELL_ALLOWED', 'cat', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_037', 'SHELL_ALLOWED', 'head', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_038', 'SHELL_ALLOWED', 'tail', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_039', 'SHELL_ALLOWED', 'wc', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_040', 'SHELL_ALLOWED', 'find', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_041', 'SHELL_ALLOWED', 'grep', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_042', 'SHELL_ALLOWED', 'egrep', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_043', 'SHELL_ALLOWED', 'awk', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_044', 'SHELL_ALLOWED', 'sed', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_045', 'SHELL_ALLOWED', 'mkdir', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_046', 'SHELL_ALLOWED', 'touch', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_047', 'SHELL_ALLOWED', 'cp', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_048', 'SHELL_ALLOWED', 'mv', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_049', 'SHELL_ALLOWED', 'ln', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_050', 'SHELL_ALLOWED', 'stat', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_051', 'SHELL_ALLOWED', 'file', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_052', 'SHELL_ALLOWED', 'du', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_053', 'SHELL_ALLOWED', 'df', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_054', 'SHELL_ALLOWED', 'tree', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_055', 'SHELL_ALLOWED', 'realpath', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_056', 'SHELL_ALLOWED', 'basename', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_057', 'SHELL_ALLOWED', 'dirname', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_058', 'SHELL_ALLOWED', 'sort', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_059', 'SHELL_ALLOWED', 'uniq', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_060', 'SHELL_ALLOWED', 'cut', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_061', 'SHELL_ALLOWED', 'tr', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_062', 'SHELL_ALLOWED', 'tee', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_063', 'SHELL_ALLOWED', 'diff', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_064', 'SHELL_ALLOWED', 'patch', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_065', 'SHELL_ALLOWED', 'echo', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_066', 'SHELL_ALLOWED', 'printf', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_067', 'SHELL_ALLOWED', 'xargs', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_068', 'SHELL_ALLOWED', 'envsubst', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_069', 'SHELL_ALLOWED', 'column', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_070', 'SHELL_ALLOWED', 'jq', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_071', 'SHELL_ALLOWED', 'yq', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_072', 'SHELL_ALLOWED', 'iconv', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_073', 'SHELL_ALLOWED', 'strings', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_074', 'SHELL_ALLOWED', 'od', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_075', 'SHELL_ALLOWED', 'hexdump', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_076', 'SHELL_ALLOWED', 'xxd', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_077', 'SHELL_ALLOWED', 'tar', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_078', 'SHELL_ALLOWED', 'gzip', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_079', 'SHELL_ALLOWED', 'gunzip', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_080', 'SHELL_ALLOWED', 'zip', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_081', 'SHELL_ALLOWED', 'unzip', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_082', 'SHELL_ALLOWED', 'bzip2', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_083', 'SHELL_ALLOWED', 'bunzip2', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_084', 'SHELL_ALLOWED', 'xz', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_085', 'SHELL_ALLOWED', 'unxz', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_086', 'SHELL_ALLOWED', 'zstd', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_087', 'SHELL_ALLOWED', 'unzstd', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_088', 'SHELL_ALLOWED', 'curl', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_089', 'SHELL_ALLOWED', 'date', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_090', 'SHELL_ALLOWED', 'env', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_091', 'SHELL_ALLOWED', 'which', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_092', 'SHELL_ALLOWED', 'whoami', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_093', 'SHELL_ALLOWED', 'uname', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_094', 'SHELL_ALLOWED', 'hostname', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_095', 'SHELL_ALLOWED', 'uptime', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_096', 'SHELL_ALLOWED', 'free', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_097', 'SHELL_ALLOWED', 'vmstat', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_098', 'SHELL_ALLOWED', 'iostat', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_shell_099', 'SHELL_ALLOWED', 'ulimit', 'Shell白名单命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_001', 'DEFAULT_ALLOW', 'ls', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_002', 'DEFAULT_ALLOW', 'cat', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_003', 'DEFAULT_ALLOW', 'head', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_004', 'DEFAULT_ALLOW', 'tail', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_005', 'DEFAULT_ALLOW', 'less', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_006', 'DEFAULT_ALLOW', 'more', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_007', 'DEFAULT_ALLOW', 'file', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_008', 'DEFAULT_ALLOW', 'stat', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_009', 'DEFAULT_ALLOW', 'tree', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_010', 'DEFAULT_ALLOW', 'od', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_011', 'DEFAULT_ALLOW', 'hexdump', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_012', 'DEFAULT_ALLOW', 'find', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_013', 'DEFAULT_ALLOW', 'grep', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_014', 'DEFAULT_ALLOW', 'egrep', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_015', 'DEFAULT_ALLOW', 'fgrep', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_016', 'DEFAULT_ALLOW', 'locate', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_017', 'DEFAULT_ALLOW', 'which', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_018', 'DEFAULT_ALLOW', 'whereis', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_019', 'DEFAULT_ALLOW', 'type', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_020', 'DEFAULT_ALLOW', 'wc', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_021', 'DEFAULT_ALLOW', 'sort', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_022', 'DEFAULT_ALLOW', 'uniq', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_023', 'DEFAULT_ALLOW', 'cut', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_024', 'DEFAULT_ALLOW', 'tr', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_025', 'DEFAULT_ALLOW', 'diff', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_026', 'DEFAULT_ALLOW', 'cmp', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_027', 'DEFAULT_ALLOW', 'comm', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_028', 'DEFAULT_ALLOW', 'join', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_029', 'DEFAULT_ALLOW', 'paste', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_030', 'DEFAULT_ALLOW', 'expand', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_031', 'DEFAULT_ALLOW', 'unexpand', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_032', 'DEFAULT_ALLOW', 'fmt', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_033', 'DEFAULT_ALLOW', 'fold', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_034', 'DEFAULT_ALLOW', 'iconv', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_035', 'DEFAULT_ALLOW', 'nl', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_036', 'DEFAULT_ALLOW', 'rev', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_037', 'DEFAULT_ALLOW', 'tac', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_038', 'DEFAULT_ALLOW', 'pwd', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_039', 'DEFAULT_ALLOW', 'whoami', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_040', 'DEFAULT_ALLOW', 'id', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_041', 'DEFAULT_ALLOW', 'groups', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_042', 'DEFAULT_ALLOW', 'users', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_043', 'DEFAULT_ALLOW', 'who', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_044', 'DEFAULT_ALLOW', 'w', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_045', 'DEFAULT_ALLOW', 'last', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_046', 'DEFAULT_ALLOW', 'lastlog', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_047', 'DEFAULT_ALLOW', 'date', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_048', 'DEFAULT_ALLOW', 'uname', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_049', 'DEFAULT_ALLOW', 'hostname', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_050', 'DEFAULT_ALLOW', 'hostid', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_051', 'DEFAULT_ALLOW', 'arch', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_052', 'DEFAULT_ALLOW', 'nproc', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_053', 'DEFAULT_ALLOW', 'df', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_054', 'DEFAULT_ALLOW', 'du', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_055', 'DEFAULT_ALLOW', 'free', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_056', 'DEFAULT_ALLOW', 'uptime', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_057', 'DEFAULT_ALLOW', 'dmesg', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_058', 'DEFAULT_ALLOW', 'ps', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_059', 'DEFAULT_ALLOW', 'pgrep', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_060', 'DEFAULT_ALLOW', 'top', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_061', 'DEFAULT_ALLOW', 'env', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_062', 'DEFAULT_ALLOW', 'printenv', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_063', 'DEFAULT_ALLOW', 'ulimit', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_064', 'DEFAULT_ALLOW', 'umask', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_065', 'DEFAULT_ALLOW', 'getconf', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_066', 'DEFAULT_ALLOW', 'basename', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_067', 'DEFAULT_ALLOW', 'dirname', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_068', 'DEFAULT_ALLOW', 'realpath', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_069', 'DEFAULT_ALLOW', 'readlink', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_070', 'DEFAULT_ALLOW', 'echo', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_071', 'DEFAULT_ALLOW', 'printf', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_072', 'DEFAULT_ALLOW', 'man', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_073', 'DEFAULT_ALLOW', 'whatis', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_074', 'DEFAULT_ALLOW', 'apropos', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_075', 'DEFAULT_ALLOW', 'info', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_076', 'DEFAULT_ALLOW', 'cal', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_077', 'DEFAULT_ALLOW', 'clear', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_078', 'DEFAULT_ALLOW', 'reset', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_079', 'DEFAULT_ALLOW', 'tty', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_080', 'DEFAULT_ALLOW', 'sleep', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_081', 'DEFAULT_ALLOW', 'test', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_082', 'DEFAULT_ALLOW', 'expr', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_083', 'DEFAULT_ALLOW', 'true', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_084', 'DEFAULT_ALLOW', 'false', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_085', 'DEFAULT_ALLOW', 'yes', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_086', 'DEFAULT_ALLOW', 'command', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_087', 'DEFAULT_ALLOW', 'builtin', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_088', 'DEFAULT_ALLOW', 'hash', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_def_089', 'DEFAULT_ALLOW', 'tsort', 'Default模式免确认命令', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_01', 'HARD_BLOCK', 'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(\*|$|\s)', 'rm -rf / 或 rm -rf /*', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_02', 'HARD_BLOCK', 'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/[^\s]', 'rm -rf 任何绝对路径', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_03', 'HARD_BLOCK', 'dd\s+.*of=/dev/(sd|hd|nvme|xvd|vd|mmcblk|dm-|loop)', 'dd 覆写磁盘', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_04', 'HARD_BLOCK', 'mkfs\.\S*\s+/dev/', 'mkfs 格式化设备', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_05', 'HARD_BLOCK', ':\(\)\s*\{.*:\|&\s*\}|fork\s+bomb', 'fork 炸弹', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_06', 'HARD_BLOCK', 'shred\s+.*/(dev|etc|boot|bin|sbin|lib|usr|var|home|root|opt|sys|proc)/', 'shred 安全擦除系统路径', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_07', 'HARD_BLOCK', 'mv\s+.*/dev/null', 'mv 到 /dev/null 销毁数据', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_08', 'HARD_BLOCK', '(?:/etc/sudoers|/etc/sudoers\.d/)', '修改 sudoers 文件', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_09', 'HARD_BLOCK', '(?:usermod|gpasswd|adduser|useradd)\s+.*\b(?:sudo|wheel|admin|root)\b', '添加用户到特权组', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_10', 'HARD_BLOCK', 'chmod\s+(?:.*[ug]\+s|[4-7]\d{2})', '设置 SUID/SGID 位', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_11', 'HARD_BLOCK', '(?:iptables\s+-(?:F|X)\b|nft\s+flush\s+ruleset|ufw\s+disable)', '清空/禁用防火墙', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_12', 'HARD_BLOCK', 'iptables\s+--flush', 'iptables 长格式清空', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_13', 'HARD_BLOCK', 'systemctl\s+(?:stop|disable|mask)\s+(?:selinux|apparmor|auditd|firewalld|iptables|ufw|fail2ban|clamav|crowdstrike)', '停止安全服务', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_14', 'HARD_BLOCK', 'setenforce\s+0', '关闭 SELinux', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_15', 'HARD_BLOCK', '/etc/pam\.d/', '修改 PAM 配置', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_16', 'HARD_BLOCK', '/etc/ssh/(?:sshd_config|ssh_config)', '修改 SSH 服务配置', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_17', 'HARD_BLOCK', '(?:>|>>|tee\s+(?:-a\s+)?)\S*authorized_keys', '写入 SSH 公钥', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_18', 'HARD_BLOCK', '(?:>|>>|tee\s+(?:-a\s+)?)\S*(?:\.bashrc|\.zshrc|\.profile|\.bash_profile|/etc/profile)', '写入 shell RC 文件', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_19', 'HARD_BLOCK', '(?:>|>>|cp|mv)\s+.*/(?:usr/)?(?:s?bin|lib(?:64)?)/', '写入系统二进制目录', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_20', 'HARD_BLOCK', 'crontab\s+-(?:[^l]|e|r)', '修改 crontab（排除 -l 列出）', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_21', 'HARD_BLOCK', 'kubectl\s+delete\s+(?:namespace|ns\b|pods?|deployment|deploy\b|statefulset|daemonset|secret|configmap|service\b|svc\b|ingress|ing\b|pvc|pv\b)', 'kubectl 删除关键资源', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_22', 'HARD_BLOCK', 'docker\s+(?:run|create).*--privileged.*(?:-v|--volume)\s+:/', 'docker 特权容器挂载宿主机根目录', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_23', 'HARD_BLOCK', 'docker\s+system\s+prune', 'docker 清理所有未使用资源', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_24', 'HARD_BLOCK', '(?:cat|head|tail|read|strings|xxd|od|hexdump).*(?:\.env|credentials|private.?key|secret|token|password|id_rsa|id_ed25519|id_ecdsa).*(?:\||>|curl|http|nc\s|socat\s|ssh\s|scp\s|rsync\s)', '敏感文件经网络外传', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_25', 'HARD_BLOCK', 'nc\s+.*-[ec]\s+(?:/bin/|/usr/bin/)?(?:bash|sh|zsh|dash|python|perl|ruby)', 'nc 反弹 shell', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_26', 'HARD_BLOCK', 'bash\s+.*-i.*>&.*/(?:dev|tmp)/tcp/', 'bash TCP 反弹 shell', 1, 1735800000
UNION ALL
    SELECT 'seed_hard_27', 'HARD_BLOCK', 'socat\s+.*EXEC:(?:bash|sh|zsh|python)', 'socat 反弹 shell', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_01', 'SOFT_BLOCK', '\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+', 'rm -rf 变体', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_02', 'SOFT_BLOCK', 'git\s+push\s+.*(?:--force|--force-with-lease)', 'git force push', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_03', 'SOFT_BLOCK', 'git\s+(?:reset\s+--hard|clean\s+-fdx)', 'git hard reset / clean', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_04', 'SOFT_BLOCK', 'git\s+branch\s+-D', 'git 强制删除分支', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_05', 'SOFT_BLOCK', '(?:curl|wget).*(?:\||>|bash|sh|python|eval|exec)', 'curl/wget 远程执行', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_06', 'SOFT_BLOCK', '(?:DROP\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\s+FROM)', '数据库删除', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_07', 'SOFT_BLOCK', 'kubectl\s+delete', 'kubectl 通用删除', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_08', 'SOFT_BLOCK', 'docker\s+(?:rm|stop|kill)', 'docker rm/stop/kill', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_09', 'SOFT_BLOCK', 'docker-compose\s+down.*-v', 'docker-compose 删除卷', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_10', 'SOFT_BLOCK', 'docker\s+volume\s+(?:rm|prune)', 'docker 删除卷', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_11', 'SOFT_BLOCK', 'helm\s+(?:delete|uninstall)', 'helm 删除 release', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_12', 'SOFT_BLOCK', '(?:kill\s+-9|pkill)', '强制杀进程', 1, 1735800000
UNION ALL
    SELECT 'seed_soft_13', 'SOFT_BLOCK', 'chmod\s+777', 'chmod 777', 1, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_security_rules);

-- ============================================================
-- 内置工具管控种子数据：从 InitialServiceImpl.insertSeed 迁移
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', '在工作空间目录执行任意 shell 命令，支持前台阻塞和后台运行', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'list_processes', '列出当前用户启动的所有后台进程及其 PID', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'kill_process', '根据 PID 终止指定的后台进程', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls);

