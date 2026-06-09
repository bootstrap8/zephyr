ALTER TABLE mcp_tools ADD COLUMN IF NOT EXISTS parameters_json text;

delete from h_sm_info where app='zephyr';
insert into h_sm_info(app,info_content) values('zephyr','{"title":"サンプルサービス"}');