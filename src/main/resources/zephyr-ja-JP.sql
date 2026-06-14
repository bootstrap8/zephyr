ALTER TABLE zephyr_mcp_tools ADD COLUMN IF NOT EXISTS parameters_json text;

ALTER TABLE zephyr_skill_configs ADD COLUMN IF NOT EXISTS scope varchar(16) DEFAULT 'user';
alter table zephyr_model_configs add column if not exists params text;

alter table zephyr_conversations add column if not exists workspace_id varchar(64);

delete from h_sm_info where app='zephyr';
insert into h_sm_info(app,info_content) values('zephyr','{"title":"ゼファーインテリジェントエージェント"}');

delete from h_menus where app='zephyr' and name in ('agents','zephyr_api');
insert into h_menus(app,name,menu_desc,url,parent_key,order_index,menu_level,icon_name,created_at) values('zephyr','agents','エージェント','/agents','-',0,1,'HAProxyIcon',1735800456);
insert into h_menus(app,name,menu_desc,url,parent_key,order_index,menu_level,icon_name,created_at) values('zephyr','zephyr_api','Zephyr エージェント','http://localhost:30733/zephyr/zephyr-ui/index.html#/chat','agents',1,2,'HAProxyIcon',1735800456);
insert into h_icons(name,icon_desc,icon_svg,icon_size,update_at) values('agent',' エージェント','<svg t="1781071343390" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="5096" width="20" height="20"><path d="M850.3 512.2c3.5-4.3 6.8-9.1 10-13.7 57.3-80.7 71.5-155.3 39.9-210.1-31.5-54.8-103.2-79.9-201.8-70.9-5.7 0.5-11.5 1.2-17.3 1.9-2.3-5.4-4.6-10.8-6.9-16C632.9 113.5 575.3 64 512 64s-120.8 49.7-162 139.6c-2.4 5.1-4.6 10.4-6.9 15.7-5.7-0.6-11.4-1.4-17.3-1.9-98.5-9.2-170.3 15.9-201.8 70.7s-17.3 129.4 39.8 210.1c3.2 4.3 6.7 9.1 10 13.6-3.5 4.3-6.8 9.2-10 13.6-57.3 80.7-71.6 155.4-39.9 210.2 27.4 47.6 84.8 72.7 163.6 72.7 12.2 0 25-0.6 38.1-1.8 5.7-0.5 11.4-1.2 17.3-1.9 2.2 5.4 4.6 10.7 6.9 16C391 910.5 448.6 960 511.9 960S632.6 910.6 674 820.6c2.4-5.1 4.6-10.5 6.9-15.7 18.4 2.5 37 3.7 55.6 3.8 78.7 0 135.9-25.3 163.3-72.5 31.6-54.8 17.6-129.4-39.7-210.1-3.2-4.7-6.7-9.3-10.2-13.9h0.4zM320.6 749.5c-73.9 6.8-127.5-8.7-147.2-42.5-19.6-33.8-6.1-87.9 37-148.4l1.5-2.1c26.9 28.4 56 54.6 87.1 78.4 5.1 38.7 13.2 76.9 24.3 114.3l-2.7 0.3z m-28.5-194.7C277 541.2 262.6 527 248.8 512c13.2-14.4 27.6-28.7 43.3-42.8-0.6 14.2-1 28.4-1 42.9s0.4 28.5 1 42.7z m7.2-165.8c-31.1 23.8-60.2 50-87.1 78.3-0.5-0.7-1.1-1.4-1.5-2.1-43-60.6-56.3-114.7-37-148.5 16.5-28.5 57.1-44 113.7-44 12.1 0 24.1 0.7 36.1 1.9-11 37.5-19.1 75.7-24.2 114.4zM659 342.6c-23.9-15.3-48.4-29.4-73.6-42.5 19.8-6.4 39.3-11.7 58.2-16 5.9 18.4 11 37.8 15.4 58.5zM512.3 121.3c38.7 0 79.7 40.1 111 108.9-37.8 9.1-74.9 21.1-110.8 36-36-14.9-73.1-27-111-36.2 31-68.7 72-108.6 110.8-108.7zM380.8 284c19 4.2 38.5 9.6 58.4 16.1-25.2 13.1-49.7 27.2-73.6 42.5 4.1-19.8 9.3-39.3 15.4-58.5h-0.2z m-15.4 397.4c23.8 15.3 48.3 29.4 73.6 42.5-19.7 6.4-39.2 11.8-58.2 16-6.2-19.2-11.3-38.7-15.4-58.5z m146.8 221.4c-39 0-79.7-40.1-111-108.9 37.8-9.1 74.8-21.1 110.8-36 36 14.9 73.1 27 111.1 36.1-31.3 68.7-72.3 108.9-110.9 108.8z m131.4-162.7c-19.7-4.4-39.2-9.8-58.4-16.1 25.2-13.1 49.8-27.2 73.8-42.5-4.3 20.8-9.5 40.3-15.4 58.6z m27.6-136c-24.9 18-50.7 34.7-77.3 50-26.6 15.3-53.9 29.3-82 41.7-56-25-109.3-55.8-158.9-91.9-3.2-30.5-4.8-61.2-4.8-91.8 0-30.7 1.6-61.4 4.8-92 49.7-36 103.1-66.7 159.2-91.7 56.1 24.9 109.3 55.7 158.9 91.9 6.5 61.1 6.5 122.7 0.1 183.8z m32.5-329.5c11.5-1.1 22.5-1.6 33-1.6 56.8 0 97.6 15.5 114 44 19.5 33.9 6 87.9-37 148.4l-1.5 2.1c-26.9-28.4-56-54.6-87.1-78.4-5.1-38.7-13.2-76.9-24.3-114.3l2.9-0.2z m28.6 194.7c15.5 14.1 30.3 28.3 43.3 42.8-13.3 14.4-27.7 28.7-43.3 42.8 0.6-14.2 1-28.5 1-42.9-0.1-14.4-0.4-28.6-1-42.7z m118.2 238.1c-19.5 33.8-73.1 49.2-147.2 42.2l-2.8-0.2c11.1-37.4 19.2-75.6 24.2-114.3 31.1-23.8 60.2-50 87.1-78.3 0.5 0.7 1.1 1.4 1.5 2.1 43 60.6 56.3 114.7 36.9 148.5h0.3z" fill="#1296db" p-id="5097"></path></svg>',20,1735800456);

ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS model_type varchar(16) DEFAULT 'llm';
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS dimensions int DEFAULT NULL;

ALTER TABLE zephyr_knowledge_base ADD COLUMN IF NOT EXISTS scope varchar(16) DEFAULT 'user';

ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS scope VARCHAR(16) DEFAULT 'user';

ALTER TABLE zephyr_knowledge_base ADD COLUMN IF NOT EXISTS graph_enabled SMALLINT DEFAULT 0;ALTER TABLE zephyr_knowledge_doc ADD COLUMN IF NOT EXISTS graph_status varchar(16);
