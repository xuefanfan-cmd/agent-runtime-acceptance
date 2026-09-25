-- =============================================================================
-- FEAT-054 共享 PG 数据面测试库 DDL（DA 侧权威建表语句副本）
--
-- 来源：deepanalyze 仓 src/store/pg-migrations/*.ts，逐句原样提取、按迁移序回放，
-- 未做任何改写或合并；每段标注来源迁移文件。回放本文件即得 DA 侧当前最终形态。
--
-- 表清单：sessions / messages（参照用，宿主不写）/ settings / session_memory / agent_skills
--
-- 注意：042 迁移对 sessions 与 agent_skills 启用 FORCE RLS（连表 owner 也受策略约束），
-- 策略依赖会话变量 app.current_org；测试库若不需要租户隔离语义，可跳过文件末 042 段，
-- 或按策略注释设置 app.current_org='*'。
-- =============================================================================

-- =============================================================================
-- sessions（001_init.ts）
-- =============================================================================
CREATE TABLE IF NOT EXISTS sessions (
  id          TEXT PRIMARY KEY,
  title       TEXT,
  kb_scope    TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- messages（001_init.ts；宿主只读参照——宿主不写本表，防双写契约见 L2 §2.12）
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

-- messages 索引（001_init.ts）
CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);

-- messages 全文检索列（019_self_evolution.ts）
ALTER TABLE messages ADD COLUMN IF NOT EXISTS search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;
CREATE INDEX IF NOT EXISTS idx_messages_search ON messages USING GIN (search_vector);

-- =============================================================================
-- settings（001_init.ts；KV 表，agent_settings 行与 providers 行均存于此）
-- =============================================================================
CREATE TABLE IF NOT EXISTS settings (
  key         TEXT PRIMARY KEY,
  value       JSONB NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- session_memory（001_init.ts）
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

-- session_memory 追加列（015_search_index_column.ts）
ALTER TABLE session_memory ADD COLUMN IF NOT EXISTS search_index_json JSONB DEFAULT NULL;

-- =============================================================================
-- agent_skills（012_agent_skills.ts）
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

-- agent_skills 追加列（014_skill_anti_hallucination_test.ts）
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS anti_hallucination_level TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS test_scenarios JSONB DEFAULT NULL;

-- agent_skills 来源追踪（020_skill_source_tracking.ts）：
-- 注意 name 的全局 UNIQUE 约束在此被替换为 (name, source) 复合唯一
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS plugin_id TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS hub_slug TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS hub_url TEXT DEFAULT NULL;
ALTER TABLE agent_skills DROP CONSTRAINT IF EXISTS agent_skills_name_key;
ALTER TABLE agent_skills ADD CONSTRAINT agent_skills_name_source_unique UNIQUE (name, source);
CREATE INDEX IF NOT EXISTS idx_agent_skills_source ON agent_skills(source);
CREATE INDEX IF NOT EXISTS idx_agent_skills_plugin_id ON agent_skills(plugin_id) WHERE plugin_id IS NOT NULL;

-- agent_skills 元数据增强（022_skill_metadata_enhancement.ts）
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS triggers TEXT[] DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS requires JSONB DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS tags TEXT[] DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS install JSONB DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS homepage TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS version TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS author TEXT DEFAULT NULL;
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS emoji TEXT DEFAULT NULL;

-- agent_skills 包目录（040_agent_skill_package_dir.ts）
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS package_dir TEXT DEFAULT NULL;

-- agent_skills 包文件数（043_agent_skill_package_file_count.ts）
ALTER TABLE agent_skills ADD COLUMN IF NOT EXISTS package_file_count INTEGER DEFAULT NULL;

-- =============================================================================
-- 租户列（039_tenant_id_columns.ts）
-- =============================================================================
ALTER TABLE sessions        ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'default-user';
ALTER TABLE agent_skills    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'default-user';

-- =============================================================================
-- 行级租户隔离（042_rls_tenant_isolation.ts）
-- 测试库如不需要租户隔离可整段跳过；启用时注意 FORCE RLS 对表 owner 同样生效。
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
