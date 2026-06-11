ALTER TABLE mcp_tools ADD COLUMN IF NOT EXISTS parameters_json text;
alter table model_configs add column if not exists params text;

alter table conversations add column if not exists workspace_id varchar(64);

delete from h_sm_info where app='zephyr';
insert into h_sm_info(app,info_content) values('zephyr','{"title":"ゼファーインテリジェントエージェント"}');