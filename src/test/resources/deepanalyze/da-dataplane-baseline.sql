-- =============================================================================
-- FEAT-069 数据面批次基线库 DDL（da-dataplane-baseline.sql）
--
-- 构成：
--   A. DA(TS) 侧权威建表语句副本（逐句取自 ../deepanalyze/da-shared-pg-ddl.sql，
--      来源 DA 仓 pg-migrations——与 FEAT-054 线同一真相源，未改写）；
--   B. FEAT-069 被测面其余 TS 形态表（workflows/workflow_logs/reports/
--      report_references/agent_teams/agent_team_members/skill_usage/agent_tasks/
--      知识库域/系统域/plugins/skills）——列以被测制品 0.1.3-feat069 的 SQL
--      列清单 + TS 迁移形状最小忠实模拟（来源标注见各段注释）；
--   C. 显式命名的两处对 sessions 外键（session_memory_session_id_fkey /
--      reports_session_id_fkey）——SUT agent-schema.sql 的拆除目标，
--      不预建则 f069-schema-02 的 FK 拆除校验空洞通过；
--   D. agent_todos 不在本文件：它是 SUT 启动期自建对象（agent-schema.sql），
--      预建会使 f069-schema-01 的建表断言空洞通过。
--
-- 回放本文件 = FEAT-069 审视所需的最小 TS 终态 + 演进前形态。
-- =============================================================================

-- =============================================================================
-- A1. sessions（001_init.ts）
-- =============================================================================
CREATE TABLE IF NOT EXISTS sessions (
  id          TEXT PRIMARY KEY,
  title       TEXT,
  kb_scope    TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- A2. messages（001_init.ts；宿主不写，防双写契约见 FEAT-054 L2 §2.12）
-- =============================================================================
CREATE TABLE IF NOT EXISTS messages (
  id          TEXT PRIMARY KEY,
  session_id  TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  role        TEXT NOT NULL
              CHECK (role IN ('user', 'assistant', 'tool')),
  content     TEXT DEFAULT '',
  metadata    JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_messages_search ON messages USING GIN (search_vector);

-- =============================================================================
-- A3. settings（001_init.ts）
-- =============================================================================
CREATE TABLE IF NOT EXISTS settings (
  key         TEXT PRIMARY KEY,
  value       JSONB NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- A4. session_memory（001_init.ts；内联 FK 自动命名 session_memory_session_id_fkey
--     —— SUT agent-schema.sql 的拆除目标之一；UNIQUE(session_id) 供 upsert ON CONFLICT）
-- =============================================================================
CREATE TABLE IF NOT EXISTS session_memory (
  id                   TEXT PRIMARY KEY,
  session_id           TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE UNIQUE,
  content              TEXT NOT NULL,
  token_count          INTEGER DEFAULT 0,
  last_token_position  INTEGER DEFAULT 0,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE session_memory ADD COLUMN IF NOT EXISTS search_index_json JSONB DEFAULT NULL;

-- =============================================================================
-- A5. agent_skills（012/014/020/022/040/043 迁移；020 起 UNIQUE 换 (name, source)）
-- =============================================================================
CREATE TABLE IF NOT EXISTS agent_skills (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL DEFAULT '',
  prompt      TEXT NOT NULL,
  tools       TEXT[] NOT NULL DEFAULT '{"*"}',
  model_role  TEXT NOT NULL DEFAULT 'main',
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_skills_active ON agent_skills(is_active) WHERE is_active = true;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS anti_hallucination_level TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS test_scenarios JSONB DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS plugin_id TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS hub_slug TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS hub_url TEXT DEFAULT NULL;
ALTER TABLE agent_skills DROP CONSTRAINT IF EXISTS agent_skills_name_key;
ALTER TABLE agent_skills ADD CONSTRAINT agent_skills_name_source_unique UNIQUE (name, source);
CREATE INDEX IF NOT EXISTS idx_agent_skills_source ON agent_skills(source);
CREATE INDEX IF NOT EXISTS idx_agent_skills_plugin_id ON agent_skills(plugin_id) WHERE plugin_id IS NOT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS triggers TEXT[] DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS requires JSONB DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS tags TEXT[] DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS install JSONB DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS homepage TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS version TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS author TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS emoji TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS package_dir TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS package_file_count INTEGER DEFAULT NULL;

-- =============================================================================
-- A6. 租户列（039_tenant_id_columns.ts）
-- =============================================================================
ALTER TABLE sessions     ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'default-user';
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'default-user';

-- =============================================================================
-- A7. 行级租户隔离（042_rls_tenant_isolation.ts；一期 fail-open：未设 app.current_org 即放行）
-- =============================================================================
ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON sessions;
CREATE POLICY tenant_isolation ON sessions
  USING (
    coalesce(current_setting('app.current_org', true), '*') = '*'
    OR tenant_id = current_setting('app.current_org', true)
  );
ALTER TABLE agent_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_skills FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agent_skills;
CREATE POLICY tenant_isolation ON agent_skills
  USING (
    coalesce(current_setting('app.current_org', true), '*') = '*'
    OR tenant_id = current_setting('app.current_org', true)
    OR source IN ('builtin','plugin','hub')
  );

-- =============================================================================
-- B1. workflows（列=DaWorkflowRepo SQL + TS 028 形态；a2a_task_id 列由 SUT 自建，不预建）
--     2026-10-26 对齐 095b7fb89（!890 合入点）：DaWorkflowRepo.insertStarted 字段完整性
--     对账新增 goal / agent_count（"TS 读面直读共享库后这两列必须有值"，SUT 启动期
--     不自建）——夹具必须承载，类型按 DaWorkflowStart（goal String / agentCount Integer 可空）。
-- =============================================================================
CREATE TABLE IF NOT EXISTS workflows (
  id             TEXT PRIMARY KEY,
  session_id     TEXT NOT NULL,
  parent_task_id TEXT,
  team_name      TEXT,
  mode           TEXT,
  goal           TEXT,
  agent_count    INTEGER,
  status         TEXT NOT NULL DEFAULT 'running'
                 CHECK (status IN ('running', 'completed', 'failed', 'cancelled')),
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  result         JSONB,
  error          TEXT,
  completed_at   TIMESTAMPTZ
);

-- =============================================================================
-- B2. workflow_logs（列=DaWorkflowLogRepo SQL；对 workflows 的 FK 为自有表之间，保留）
-- =============================================================================
CREATE TABLE IF NOT EXISTS workflow_logs (
  id           TEXT PRIMARY KEY,
  workflow_id  TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
  agent_id     TEXT,
  role         TEXT,
  turn         INTEGER,
  event_type   TEXT NOT NULL,
  tool_name    TEXT,
  content      JSONB,
  duration_ms  BIGINT,
  model_id     TEXT,
  tokens_in    INTEGER,
  tokens_out   INTEGER,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_workflow_logs_workflow_id ON workflow_logs(workflow_id);

-- =============================================================================
-- B3. reports / report_references（列=DaReportRepo SQL；entities 无 CAST 直插 → TEXT（2026-10-21 实测校准，JSONB 会触发类型不匹配被 writer 永久类隔离）；
--     026 迁移显式命名 FK reports_session_id_fkey —— SUT 拆除目标之二；
--     report_references 对 reports 的 FK 为自有表之间，保留）
-- =============================================================================
CREATE TABLE IF NOT EXISTS reports (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL,
  message_id    TEXT,
  title         TEXT,
  clean_content TEXT,
  raw_content   TEXT,
  entities      JSONB,
  tenant_id     TEXT NOT NULL DEFAULT 'default-user',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT reports_session_id_fkey FOREIGN KEY (session_id)
    REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS report_references (
  id         BIGSERIAL PRIMARY KEY,
  report_id  TEXT NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
  ref_index  INTEGER NOT NULL DEFAULT 0,
  doc_id     TEXT,
  page_id    TEXT,
  title      TEXT,
  level      TEXT,
  snippet    TEXT,
  highlight  TEXT
);
CREATE INDEX IF NOT EXISTS idx_reports_session_id ON reports(session_id);

-- =============================================================================
-- B4. agent_teams / agent_team_members（列=DaAgentTeamRepo SQL；mode 词表=TS 004 CHECK）
-- =============================================================================
CREATE TABLE IF NOT EXISTS agent_teams (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,
  description   TEXT,
  mode          TEXT NOT NULL DEFAULT 'pipeline'
                CHECK (mode IN ('pipeline', 'graph', 'council', 'parallel')),
  is_active     BOOLEAN NOT NULL DEFAULT true,
  cross_review  BOOLEAN NOT NULL DEFAULT false,
  enable_skills BOOLEAN NOT NULL DEFAULT false,
  model_config  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS agent_team_members (
  id               TEXT PRIMARY KEY,
  team_id          TEXT NOT NULL REFERENCES agent_teams(id) ON DELETE CASCADE,
  role             TEXT NOT NULL,
  system_prompt    TEXT,
  task             TEXT,
  perspective      TEXT,
  depends_on       TEXT NOT NULL DEFAULT '[]',
  condition_config TEXT,
  tools            TEXT NOT NULL DEFAULT '[]',
  sort_order       INTEGER NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_team_members_team_id ON agent_team_members(team_id);

-- =============================================================================
-- B5. skill_usage（列=DaAgentSkillStore SQL + 演进生命周期代表列——
--     lifecycle_status/agent_created_at 归 agentevolving，用于"按列分写不触碰"断言）
-- =============================================================================
CREATE TABLE IF NOT EXISTS skill_usage (
  skill_id         TEXT PRIMARY KEY,
  use_count        INTEGER NOT NULL DEFAULT 0,
  last_used_at     TIMESTAMPTZ,
  lifecycle_status TEXT,
  agent_created_at TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- B6. 演进三表（019_self_evolution.ts：skill_versions/agent_memory 建表即交付，空表属正常）
-- =============================================================================
CREATE TABLE IF NOT EXISTS skill_versions (
  id         TEXT PRIMARY KEY,
  skill_id   TEXT,
  version    TEXT,
  content    TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS agent_memory (
  id         TEXT PRIMARY KEY,
  scope      TEXT,
  content    TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- B7. agent_tasks（上游落库参照表；宿主零权限面。列按"任务生命周期状态"最小形态）
-- =============================================================================
CREATE TABLE IF NOT EXISTS agent_tasks (
  id          TEXT PRIMARY KEY,
  session_id  TEXT,
  a2a_task_id TEXT,
  status      TEXT NOT NULL DEFAULT 'pending',
  context     JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- B8. 知识库域（一期属主=作战部；agent 一期只读例外面=wiki_pages/wiki_links 两表。
--     列为权限验证所需最小形态）
-- =============================================================================
CREATE TABLE IF NOT EXISTS knowledge_bases (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS documents (
  id                TEXT PRIMARY KEY,
  knowledge_base_id TEXT,
  title             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS wiki_pages (
  id         TEXT PRIMARY KEY,
  title      TEXT,
  content    TEXT,
  search_vec TSVECTOR,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS wiki_links (
  id             TEXT PRIMARY KEY,
  source_page_id TEXT,
  target_page_id TEXT
);
CREATE TABLE IF NOT EXISTS embeddings (
  id           TEXT PRIMARY KEY,
  document_id  TEXT,
  embedding    TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS anchors (
  id          TEXT PRIMARY KEY,
  document_id TEXT,
  anchor      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tags (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS document_tags (
  document_id TEXT,
  tag_id      TEXT,
  PRIMARY KEY (document_id, tag_id)
);
CREATE TABLE IF NOT EXISTS search_cache (
  id          TEXT PRIMARY KEY,
  query_hash  TEXT,
  result      JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- B9. 系统域 + plugins/skills（一期属主=作战部；agent 账号零权限面。最小形态）
-- =============================================================================
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,
  username   TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS audit_log (
  id         BIGSERIAL PRIMARY KEY,
  actor      TEXT,
  action     TEXT,
  detail     JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS cron_jobs (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  schedule    TEXT,
  enabled     BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS module_states (
  id          TEXT PRIMARY KEY,
  module      TEXT NOT NULL,
  state       JSONB,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS config_versions (
  id         BIGSERIAL PRIMARY KEY,
  key        TEXT NOT NULL,
  value      JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS extension_requests (
  id         TEXT PRIMARY KEY,
  requester  TEXT,
  payload    JSONB,
  status     TEXT NOT NULL DEFAULT 'pending',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS plugins (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  enabled    BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS skills (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
