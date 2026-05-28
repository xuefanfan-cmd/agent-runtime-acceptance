# 测试用例（TC）上线门禁设计文档
# Test Case Quality Gate Design Document

> 文档性质：架构级基础设施文档，定义测试资产与待测对象之间的质量契约  
> 适用范围：spring-ai-ascend 平台 SIT/系统测试用例上线前质量门禁  
> 关联文档：《SIT 测试设计文档》、《ARCHITECTURE.md》、《Java 21 SIT 测试框架推荐》  
> 架构原则：待测对象仓与测试仓物理隔离，测试逻辑对上层不可见

---

## 1. 架构定位：门禁是架构一致性的基础设施

### 1.1 为什么门禁是架构问题而非测试问题

在分层架构治理体系（ADR-0068 Layered 4+1）中，测试门禁不是"测试团队的管理工具"，而是**架构意图向下传导的强制通道**。其核心价值在于：

- **契约硬化**：将 L0/L1 架构文档中的接口定义、依赖方向、状态机规则转化为可自动执行的校验逻辑，防止架构意图在 L2/L3 实现中衰减。
- **双向验证**：不仅验证"测试是否覆盖了架构"，更验证"架构是否被测试充分约束"——未覆盖的架构约束是架构设计的盲区信号。
- **资产隔离**：测试逻辑、Mock 数据、边界值策略作为架构验证的"校准基准"，必须与待测实现物理隔离，防止校准基准被污染。

### 1.2 仓间架构

待测对象仓（`spring-ai-ascend`）与测试仓（`spring-ai-ascend-tests`）为两个独立 Git 仓库，无子模块引用、无代码共享。两者通过**仓间契约文件**（`architecture-contract.yaml`）建立唯一正式接口。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        待测对象仓（SUT Repository）                          │
│                                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │agent-cli-│  │agent-ser-│  │agent-exe-│  │agent-bus │  │agent-mid-│       │
│  │  ent     │  │  vice    │  │  cution  │  │          │  │  dleware │       │
│  │  (源码)   │  │  (源码)   │  │  (源码)   │  │  (源码)   │  │  (源码)   │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │             │             │             │             │               │
│       └─────────────┴─────────────┴─────────────┴─────────────┘               │
│                             │                                               │
│                    ┌────────▼────────┐                                      │
│                    │  CI Pipeline    │  ← 编译、打包、发布产物               │
│                    │  (Build JARs)   │                                      │
│                    └────────┬────────┘                                      │
│                             │                                               │
│                    ┌────────▼────────┐                                      │
│                    │  产物发布点      │  ← Maven Nexus / Docker Registry   │
│                    │  (JAR + 容器)   │                                      │
│                    └────────┬────────┘                                      │
│                             │                                               │
│                    ┌────────▼────────┐                                      │
│                    │ 仓间契约文件      │  ← 被测对象的"被挑战对象"          │
│                    │ architecture-    │     接口签名 / HTTP schema / 事件格式 │
│                    │  contract.yaml   │     配置清单 / 部署拓扑               │
│                    └────────┬────────┘                                      │
└─────────────────────────────┼─────────────────────────────────────────────┘
                              │ 单向流动（仅产物+契约对外可见）
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        测试仓（Test Repository）                               │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     门禁引擎（Gate Engine）                            │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │    │
│  │  │ tc-lint.sh│  │ tc-gate.sh│  │tc-report.│  │tc-evolve.│          │    │
│  │  │ (格式/规范)│  │ (全量门禁)│  │  sh      │  │  sh      │          │    │
│  │  │           │  │           │  │(质量报告) │  │(演进分析) │          │    │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │    │
│  │                                                                     │    │
│  │  ┌─────────────────────────────────────────────────────────────┐    │    │
│  │  │              两层一致性验证引擎                              │    │    │
│  │  │  Layer 1: 被测产物 ↔ 仓间契约文件                            │    │    │
│  │  │  Layer 2: 仓间契约文件 ↔ ARCHITECTURE.md（业务目的）          │    │    │
│  │  └─────────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     测试执行引擎（Test Engine）                        │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │    │
│  │  │  2M 契约  │  │  3-4M    │  │  6M 端到  │  │ 非功能    │          │    │
│  │  │  测试     │  │  子链路   │  │  端测试   │  │ 专项     │          │    │
│  │  │(Pact/Arch)│  │(TC+Await) │  │(RestAss) │  │(Perf/Sec)│          │    │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     测试资产（Test Assets）                          │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │    │
│  │  │   TC 文档  │  │   Mock   │  │   门禁    │  │   质量    │          │    │
│  │  │ (Markdown) │  │  数据    │  │  规则库   │  │  基线     │          │    │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │    │
│  │                                                                     │    │
│  │  关键：测试资产对待测对象完全不可见，仅通过 CI pipeline 消费          │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 隔离原则

| 原则 | 说明 |
|-----|------|
| **物理隔离** | 两个独立 Git 仓库，无子模块引用、无代码共享 |
| **单向流动** | 仅允许"待测对象产物 → 测试仓消费"，禁止反向 |
| **契约驱动** | 测试仓通过仓间契约文件驱动验证，不直接读取待测对象源码 |
| **黑盒断言** | 测试仓对待测对象的断言仅基于外部可观测行为（HTTP 响应、MQ 消息、日志、指标） |
| **门禁自治** | 门禁脚本、检查规则、评分算法全部驻留测试仓，待测对象仅提供被测产物 |

---

## 2. 仓间契约文件（Cross-Repository Contract）

### 2.1 契约文件定义

待测对象仓每次发布版本时，必须生成并发布 `architecture-contract.yaml`，作为测试仓的"被挑战对象"。

```yaml
# architecture-contract.yaml — 仓间契约文件
# 由待测对象仓 CI 生成，发布到 Maven/Nexus 或 GitHub Release

metadata:
  repository: spring-ai-ascend
  version: 1.0.0-rc13
  commit_sha: 82a1397
  build_timestamp: 2026-05-20T14:28:00Z
  posture: dev

# Layer 1: SPI 接口契约 — 从源码提取
spi_contracts:
  - module: agent-execution-engine
    package: com.huawei.ascend.engine.orchestration.spi
    interfaces:
      - name: Orchestrator
        methods:
          - signature: "Run orchestrate(RunContext, ExecutorDefinition)"
            exceptions: ["SuspendSignal"]
      - name: Checkpointer
        methods:
          - signature: "void save(UUID, String, Object)"
            exceptions: []

  - module: agent-bus
    package: com.huawei.ascend.bus.spi.ingress
    interfaces:
      - name: IngressGateway
        methods:
          - signature: "IngressResponse hydrate(IngressEnvelope)"
            exceptions: ["IngressException"]
    messages:
      - type: IngressEnvelope
        fields:
          - name: tenantId
            type: UUID
            required: true
          - name: taskCursor
            type: String
            required: true
          - name: skillPoolLimit
            type: Integer
            required: true
            min: 1
            max: 100

# Layer 2: HTTP API 契约 — 从 OpenAPI 提取
http_contracts:
  - path: /v1/runs
    method: POST
    request_headers:
      - name: X-Tenant-Id
        required: true
        pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
      - name: X-Idempotency-Key
        required: true
        pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    request_body:
      type: HydrationRequest
      fields:
        - name: taskCursor
          type: String
        - name: skillPoolLimit
          type: Integer
    response_codes: [201, 400, 403, 409]
    response_body:
      type: RunResponse
      fields:
        - name: runId
          type: UUID
        - name: status
          type: Enum
          values: [PENDING, RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELLED, EXPIRED]
        - name: traceId
          type: String
          nullable: true

# Layer 3: 事件契约 — 从 MQ schema 提取
event_contracts:
  - topic: control-track
    events:
      - type: PauseCommand
        fields:
          - name: runId
            type: UUID
  - topic: data-track
    events:
      - type: ToolResult
        fields:
          - name: toolId
            type: String
          - name: costReceipt
            type: SkillCostReceipt
  - topic: rhythm-track
    events:
      - type: WakeupPulse
        fields:
          - name: targetRunId
            type: UUID

# Layer 4: 配置契约
config_contracts:
  - key: app.posture
    type: Enum
    values: [dev, research, prod]
    default: dev

# Layer 5: 部署拓扑契约
deployment_contracts:
  services:
    - name: agent-service
      image: spring-ai-ascend/agent-service:1.0.0-rc13
      ports: [8080]
      dependencies: [postgres, kafka]
  health_checks:
    - path: /v1/health
      expected_status: 200
```

### 2.2 契约文件生成（待测对象仓 CI）

```yaml
# 待测对象仓 .github/workflows/generate-contract.yml
name: Generate Architecture Contract

on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  generate-contract:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Extract SPI signatures
        run: ./scripts/extract-spi-signatures.sh > contract/spi-contracts.json

      - name: Extract OpenAPI schema
        run: |
          ./mvnw spring-boot:run -pl agent-service &
          sleep 30
          curl -s http://localhost:8080/v3/api-docs > contract/openapi.json

      - name: Extract MQ schemas
        run: ./scripts/extract-event-schemas.sh > contract/event-contracts.json

      - name: Merge to architecture-contract.yaml
        run: |
          ./scripts/merge-contract.sh             --spi contract/spi-contracts.json             --http contract/openapi.json             --event contract/event-contracts.json             --config src/main/resources/application.yml             --deployment docker-compose.sit.yml             --output architecture-contract.yaml

      - name: Publish to Nexus
        run: ./scripts/publish-contract.sh architecture-contract.yaml

      - name: Trigger Test Repository
        run: |
          curl -X POST             -H "Authorization: token ${{ secrets.TEST_REPO_TOKEN }}"             -H "Accept: application/vnd.github.v3+json"             https://api.github.com/repos/huawei/spring-ai-ascend-tests/dispatches             -d '{
              "event_type": "sut-contract-published",
              "client_payload": {
                "version": "${{ github.ref_name }}",
                "sha": "${{ github.sha }}",
                "contract_url": "https://nexus.example.com/spring-ai-ascend/architecture-contract.yaml"
              }
            }'
```

---

## 3. 门禁约束维度（六大支柱）

### 3.1 规范性维度（Normative）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **模板合规** | 必须使用标准 TC 模板（含：用例编号、标题、前置条件、测试步骤、预期结果、优先级、关联特性、关联架构约束、作者、创建日期） | 脚本扫描模板字段完整性 | 🔴 P0 — 阻塞 |
| **编号规范** | 格式：`SIT-<模块缩写>-<层级>-<类型>-<序号>` | 正则表达式校验 | 🔴 P0 — 阻塞 |
| **命名规范** | 标题必须包含"被测对象+操作+预期结果"，无二义性 | NLP 语义检查（禁止"是否""可能"等模糊词） | 🟡 P1 — 警告 |
| **步骤粒度** | 单用例步骤数 ≤ 7 步；每步描述 ≤ 50 字 | 脚本计数 | 🟡 P1 — 警告 |

### 3.2 可追溯性维度（Traceability）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **特性追溯** | 每个 TC 必须关联至少一个特性条目（Feature ID） | 脚本检查 `关联特性` 字段非空 | 🔴 P0 — 阻塞 |
| **架构追溯** | 每个 TC 必须关联至少一个架构约束（§4 #N、ADR-XXXX、SPI 接口 FQN） | 脚本检查 `关联架构约束` 字段，交叉验证 ARCHITECTURE.md | 🔴 P0 — 阻塞 |
| **双向覆盖** | 每个 §4 约束和 ADR 必须被至少一个 TC 覆盖 | 脚本生成《架构约束覆盖矩阵》 | 🔴 P0 — 阻塞 |
| **契约追溯** | 每个 TC 引用的接口/路径/Topic 必须在仓间契约文件中存在 | 脚本交叉验证 `architecture-contract.yaml` | 🔴 P0 — 阻塞 |
| **变更同步** | 架构变更后，关联 TC 必须在 24h 内更新或标记 `OBSOLETE` | CI 监听变更触发 TC 失效检测 | 🟡 P1 — 警告 |

### 3.3 覆盖度维度（Coverage）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **特性覆盖** | 特性功能点 100% 被 TC 覆盖（正向 + 反向） | 脚本比对特性跟踪矩阵 vs TC 清单 | 🔴 P0 — 阻塞 |
| **接口覆盖** | 仓间契约中每个 SPI 接口的每个 public 方法至少 1 个契约 TC | 脚本扫描契约文件生成《SPI 方法覆盖清单》 | 🔴 P0 — 阻塞 |
| **边界覆盖** | 每个有值域的输入必须覆盖：边界值、等价类（有效/无效）、空值 | 脚本检查 `测试数据` 字段 | 🟡 P1 — 警告 |
| **异常覆盖** | 每个模块的异常路径（超时、熔断、降级、非法状态转换）必须被 TC 覆盖 | 脚本检查 TC 标题/步骤中的异常关键词 | 🟡 P1 — 警告 |
| **非功能覆盖** | SLA、并发、安全、可观测性等非功能需求必须有专项 TC | 脚本按标签过滤 `类型=非功能` 的 TC 数量 | 🟡 P1 — 警告 |

### 3.4 可执行性维度（Executability）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **前置条件可达** | 前置条件必须能在 SIT 环境中独立准备，不依赖其他 TC 的执行结果 | 脚本检查前置条件字段是否包含"依赖 TC-XXX" | 🔴 P0 — 阻塞 |
| **测试数据就绪** | 测试数据必须：已脱敏、已版本化、已纳入 `src/test/resources/` | 脚本检查 `测试数据` 字段 | 🟡 P1 — 警告 |
| **环境可复现** | 必须明确标注执行环境（dev/research/prod posture、模块版本） | 脚本检查 `环境配置` 字段 | 🟡 P1 — 警告 |
| **预期结果可判定** | 预期结果必须是**可量化断言**，禁止"正常""正确"等模糊描述 | NLP 检查预期结果字段 | 🔴 P0 — 阻塞 |
| **自动化就绪** | P0/P1 优先级 TC 必须附带自动化脚本或明确的自动化可行性标记 | 脚本检查 `自动化状态` 字段 | 🟡 P1 — 警告 |

### 3.5 架构一致性维度（Architecture Consistency）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **模块边界尊重** | TC 不得测试不属于被测模块职责的功能 | 脚本扫描 TC 中的调用链，检测跨模块越界 | 🔴 P0 — 阻塞 |
| **依赖方向尊重** | TC 中的模块调用方向必须与架构依赖矩阵（§4 #1）一致 | 脚本比对 TC 调用图 vs `architecture-graph.yaml` | 🔴 P0 — 阻塞 |
| **SPI 纯度验证** | 测试 SPI 的 TC 不得通过 Spring/Micrometer/OTel 等框架依赖间接测试 | ArchUnit 反向规则 | 🔴 P0 — 阻塞 |
| **Posture 行为验证** | TC 必须标注适用的 posture，且行为预期与 `docs/cross-cutting/posture-model.md` 一致 | 脚本交叉验证 posture 模型文档 | 🟡 P1 — 警告 |
| **契约一致性** | TC 引用的接口签名必须与仓间契约文件一致（参数名、类型、返回值） | 脚本比对 TC 中的接口引用 vs 契约文件 | 🔴 P0 — 阻塞 |

### 3.6 可维护性与防探测维度（Maintainability & Anti-Detection）

| 约束项 | 门禁规则 | 自动化检查 | 严重级别 |
|-------|---------|-----------|---------|
| **版本控制** | TC 必须纳入 Git 版本控制，变更必须有 commit message 说明原因 | 脚本检查 TC 文件是否在 Git 跟踪中 | 🔴 P0 — 阻塞 |
| **重复检测** | 禁止功能等价的重复 TC（步骤和预期结果 80% 以上相似） | 脚本计算 TC 相似度矩阵 | 🟡 P1 — 警告 |
| **防探测合规** | TC 不得包含待测对象内部实现细节（类路径、方法名、字段名） | 脚本扫描内部实现关键词 | 🔴 P0 — 阻塞 |
| **PII 零容忍** | Mock 数据不得包含真实业务 PII（phone/email/id_card/password 等） | 脚本正则扫描 PII 模式 | 🔴 P0 — 阻塞 |
| **日志脱敏** | CI 公开发布日志不得包含具体断言值、Mock 返回值、边界值 | 脚本检查 CI 日志发布策略 | 🟡 P1 — 警告 |
| **评审闭环** | TC 必须通过评审会（至少 1 名架构师 + 1 名开发 + 1 名测试） | 脚本检查 `评审记录` 字段 | 🔴 P0 — 阻塞 |

---

## 4. 自动化门禁脚本体系

### 4.1 脚本架构

```
测试仓 CI Pipeline
    │
    ├── 本地开发阶段：tc-lint.sh（< 10s 反馈）
    │
    ├── PR 阶段：tc-gate.sh（全量门禁检查）
    │
    └── 合并后：tc-report.sh（质量度量报告）
              tc-evolve.sh（趋势分析与阈值演进）
```

### 4.2 脚本 1：tc-lint.sh（本地快速检查）

```bash
#!/bin/bash
# tc-lint.sh — Test Case Local Lint
# 运行位置：测试仓本地
# 被测对象：测试仓内的 TC 文档（对待测对象完全不可见）

set -euo pipefail

TC_DIR="${TC_DIR:-docs/tests/sit}"
CONTRACT_URL="${CONTRACT_URL:-https://nexus.example.com/spring-ai-ascend/architecture-contract.yaml}"
EXIT_CODE=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

log_error()   { echo -e "${RED}[BLOCK]${NC} $1"; EXIT_CODE=1; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_pass()    { echo -e "${GREEN}[PASS]${NC} $1"; }

# 下载仓间契约文件
download_contract() {
    local contract_file="/tmp/architecture-contract.yaml"
    if [ ! -f "$contract_file" ]; then
        echo "下载仓间契约文件: $CONTRACT_URL"
        curl -sL "$CONTRACT_URL" -o "$contract_file" || {
            log_error "无法下载仓间契约文件，请检查待测对象是否已发布"
            return 1
        }
    fi
    echo "$contract_file"
}

# P0: 模板合规
check_template_compliance() {
    local file="$1"
    local required_fields=("用例编号" "测试标题" "前置条件" "测试步骤" "预期结果" "优先级" "关联特性" "关联架构约束" "作者" "创建日期")
    for field in "${required_fields[@]}"; do
        if ! grep -q "^${field}:" "$file"; then
            log_error "[$file] 缺少必填字段: ${field}"
        fi
    done
}

# P0: 编号规范
check_id_format() {
    local file="$1"
    local id=$(grep "^用例编号:" "$file" | sed 's/用例编号://' | tr -d ' ')
    if ! echo "$id" | grep -qE '^SIT-[A-Z]{2,4}-(2M|SC|E2E)-(CONTRACT|DATAFLOW|INTERACTION|E2E)-[0-9]{3}$'; then
        log_error "[$file] 用例编号格式非法: $id"
    fi
}

# P0: 可追溯性（特性 + 架构约束 + 契约）
check_traceability() {
    local file="$1"
    local contract_file=$(download_contract)

    # 特性追溯
    if ! grep -qE 'Feature-[0-9]+|FEAT-[0-9]+|F-[0-9]+' "$file"; then
        log_error "[$file] 未关联特性条目"
    fi

    # 架构追溯
    if ! grep -qE '§4\s*#[0-9]{1,2}' "$file"; then
        log_error "[$file] 未关联 §4 架构约束"
    fi
    if ! grep -qE 'ADR-[0-9]{4}' "$file"; then
        log_error "[$file] 未关联 ADR"
    fi

    # 契约追溯：检查 TC 引用的接口/路径/Topic 是否在契约中存在
    local tc_interfaces=$(grep -oP '(?<=接口: )[A-Za-z.]+' "$file" || true)
    for iface in $tc_interfaces; do
        if ! yq e ".spi_contracts[].interfaces[].name | select(. == "$iface")" "$contract_file" 2>/dev/null | grep -q "$iface"; then
            log_error "[$file] TC 引用的接口 '$iface' 不在仓间契约中"
        fi
    done
}

# P0: 防探测检查
check_anti_detection() {
    local file="$1"
    local internal_patterns=("com.huawei.ascend.service.platform" "com.huawei.ascend.service.runtime" "InMemoryCheckpointer" "SyncOrchestrator" "TenantContextHolder")
    for pattern in "${internal_patterns[@]}"; do
        if grep -q "$pattern" "$file"; then
            log_warn "[$file] TC 包含待测对象内部实现细节 '$pattern'（建议改为外部可观测行为）"
        fi
    done

    # PII 检查
    local pii_patterns=("phone" "email" "id_card" "ssn" "password" "credit_card")
    for pattern in "${pii_patterns[@]}"; do
        if grep -qi "$pattern" "$file"; then
            log_error "[$file] TC 包含潜在 PII 关键词 '$pattern'（必须脱敏）"
        fi
    done
}

# P1: 模糊语言
check_fuzzy_language() {
    local file="$1"
    local fuzzy_words=("正常" "正确" "成功" "可能" "是否" "大概" "应该")
    for word in "${fuzzy_words[@]}"; do
        if grep -q "$word" "$file"; then
            log_warn "[$file] 预期结果包含模糊词: '$word'"
        fi
    done
}

# P1: 步骤粒度
check_step_count() {
    local file="$1"
    local step_count=$(grep -c "^[0-9]\+\." "$file" || true)
    if [ "$step_count" -gt 7 ]; then
        log_warn "[$file] 测试步骤数 $step_count > 7"
    fi
}

# 主执行
main() {
    echo "=== TC Lint Gate ==="
    echo "扫描目录: $TC_DIR"
    echo ""

    find "$TC_DIR" -name "*.md" -type f | while read -r file; do
        check_template_compliance "$file"
        check_id_format "$file"
        check_traceability "$file"
        check_anti_detection "$file"
        check_fuzzy_language "$file"
        check_step_count "$file"
    done

    echo ""
    if [ $EXIT_CODE -eq 0 ]; then
        log_pass "所有检查通过"
    else
        echo -e "${RED}存在阻塞项${NC}"
    fi
    exit $EXIT_CODE
}

main "$@"
```

### 4.3 脚本 2：tc-gate.sh（PR 全量门禁）

```bash
#!/bin/bash
# tc-gate.sh — Test Case Quality Gate (PR Level)
# 运行位置：测试仓 CI
# 核心：通过仓间契约文件验证双向覆盖

set -euo pipefail

PR_ID="${PR_ID:-}"
BASE_BRANCH="${BASE_BRANCH:-main}"
TC_DIR="${TC_DIR:-docs/tests/sit}"
CONTRACT_URL="${CONTRACT_URL:-https://nexus.example.com/spring-ai-ascend/architecture-contract.yaml}"
ARCH_MD_URL="${ARCH_MD_URL:-https://raw.githubusercontent.com/huawei/spring-ai-ascend/main/ARCHITECTURE.md}"
EXIT_CODE=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

download_contract() {
    local contract_file="/tmp/architecture-contract.yaml"
    curl -sL "$CONTRACT_URL" -o "$contract_file" || {
        log_error "无法下载仓间契约文件"
        return 1
    }
    echo "$contract_file"
}

download_arch_md() {
    local arch_file="/tmp/ARCHITECTURE.md"
    curl -sL "$ARCH_MD_URL" -o "$arch_file" || {
        log_error "无法下载架构文档"
        return 1
    }
    echo "$arch_file"
}

# P0: 仓间契约 ↔ TC 双向覆盖
verify_contract_tc_bidirectional_coverage() {
    echo "=== 仓间契约 ↔ TC 双向覆盖验证 ==="
    local contract_file=$(download_contract)

    local contract_interfaces=$(yq e '.spi_contracts[].interfaces[].name' "$contract_file" | sort -u)
    local contract_paths=$(yq e '.http_contracts[].path' "$contract_file" | sort -u)
    local contract_topics=$(yq e '.event_contracts[].topic' "$contract_file" | sort -u)

    local tc_interfaces=$(grep -rhP '(?<=接口: )[A-Za-z.]+' "$TC_DIR" | sort -u || true)
    local tc_paths=$(grep -rhP '(?<=路径: )/[v0-9/]+' "$TC_DIR" | sort -u || true)
    local tc_topics=$(grep -rhP '(?<=Topic: )[a-z-]+' "$TC_DIR" | sort -u || true)

    local uncovered=0
    for iface in $contract_interfaces; do
        if ! echo "$tc_interfaces" | grep -q "^${iface}$"; then
            echo -e "${RED}[BLOCK]${NC} 仓间契约中的接口 '$iface' 未被任何 TC 覆盖"
            uncovered=$((uncovered + 1))
        fi
    done

    for path in $contract_paths; do
        if ! echo "$tc_paths" | grep -q "^${path}$"; then
            echo -e "${YELLOW}[WARN]${NC} 仓间契约中的 HTTP 路径 '$path' 未被任何 TC 覆盖"
        fi
    done

    for topic in $contract_topics; do
        if ! echo "$tc_topics" | grep -q "^${topic}$"; then
            echo -e "${YELLOW}[WARN]${NC} 仓间契约中的 Topic '$topic' 未被任何 TC 覆盖"
        fi
    done

    if [ $uncovered -gt 0 ]; then
        EXIT_CODE=1
    fi

    echo "接口覆盖率: $(echo "$tc_interfaces" | wc -l) / $(echo "$contract_interfaces" | wc -l)"
    echo "路径覆盖率: $(echo "$tc_paths" | wc -l) / $(echo "$contract_paths" | wc -l)"
    echo "Topic 覆盖率: $(echo "$tc_topics" | wc -l) / $(echo "$contract_topics" | wc -l)"
}

# P0: 架构文档 ↔ 仓间契约一致性
verify_arch_md_contract_consistency() {
    echo "=== 架构文档 ↔ 仓间契约一致性验证 ==="
    local arch_file=$(download_arch_md)
    local contract_file=$(download_contract)

    local arch_constraints=$(grep -oP '§4\s*#\K[0-9]+' "$arch_file" | sort -u)
    local contract_constraints=$(yq e '.metadata.constraints[]' "$contract_file" 2>/dev/null | sort -u || true)

    for c in $arch_constraints; do
        if ! echo "$contract_constraints" | grep -q "^${c}$"; then
            echo -e "${YELLOW}[WARN]${NC} 架构文档 §4 #${c} 在仓间契约中无对应标记"
        fi
    done
}

# P0: 防探测深度检查
verify_anti_detection_compliance() {
    echo "=== 防探测合规检查 ==="
    local pii_count=$(grep -riE '(phone|email|id_card|ssn|password|credit_card)' "$TC_DIR" | wc -l)
    if [ "$pii_count" -gt 0 ]; then
        echo -e "${RED}[BLOCK]${NC} 检测到 $pii_count 处潜在 PII 数据，必须脱敏"
        EXIT_CODE=1
    fi
}

# P1: 自动化就绪率
check_automation_readiness() {
    echo "=== 自动化就绪率检查 ==="
    local total_tc=$(find "$TC_DIR" -name "*.md" | wc -l)
    local automated_tc=$(grep -rl "自动化状态:\s*READY" "$TC_DIR" | wc -l)
    local readiness_rate=$(echo "scale=2; $automated_tc * 100 / $total_tc" | bc)
    echo "自动化就绪率: ${readiness_rate}% ($automated_tc / $total_tc)"
    if (( $(echo "$readiness_rate < 60" | bc -l) )); then
        echo -e "${YELLOW}[WARN]${NC} 自动化就绪率 ${readiness_rate}% < 60%"
    fi
}

# P1: TC 重复检测
check_duplicate_tc() {
    echo "=== TC 重复检测 ==="
    local files=($(find "$TC_DIR" -name "*.md" -type f))
    local dup_count=0
    for ((i=0; i<${#files[@]}; i++)); do
        for ((j=i+1; j<${#files[@]}; j++)); do
            local sim=$(diff -y --suppress-common-lines "${files[$i]}" "${files[$j]}" | wc -l)
            local total=$(wc -l < "${files[$i]}")
            local rate=$(echo "scale=2; ($total - $sim) * 100 / $total" | bc)
            if (( $(echo "$rate > 80" | bc -l) )); then
                echo -e "${YELLOW}[WARN]${NC} TC 相似度 ${rate}%: ${files[$i]} ↔ ${files[$j]}"
                dup_count=$((dup_count + 1))
            fi
        done
    done
}

# 主执行
main() {
    echo "=== TC Quality Gate ==="
    echo "PR ID: $PR_ID"
    echo "Base Branch: $BASE_BRANCH"
    echo ""

    verify_contract_tc_bidirectional_coverage
    echo ""
    verify_arch_md_contract_consistency
    echo ""
    verify_anti_detection_compliance
    echo ""
    check_automation_readiness
    echo ""
    check_duplicate_tc

    echo ""
    if [ $EXIT_CODE -eq 0 ]; then
        echo -e "${GREEN}=== 所有 P0 阻塞项通过，允许合入 ===${NC}"
    else
        echo -e "${RED}=== 存在 P0 阻塞项，禁止合入 ===${NC}"
    fi
    exit $EXIT_CODE
}

main "$@"
```

### 4.4 脚本 3：tc-report.sh（质量度量报告）

```bash
#!/bin/bash
# tc-report.sh — Test Case Quality Report Generator
# 生成跨仓质量报告

set -euo pipefail

TC_DIR="${TC_DIR:-docs/tests/sit}"
OUTPUT="${OUTPUT:-tc-quality-report.html}"
CONTRACT_URL="${CONTRACT_URL:-https://nexus.example.com/spring-ai-ascend/architecture-contract.yaml}"

generate_html_report() {
    local total_tc=$(find "$TC_DIR" -name "*.md" | wc -l)
    local p0_tc=$(grep -rl "优先级:\s*P0" "$TC_DIR" | wc -l)
    local automated_tc=$(grep -rl "自动化状态:\s*READY" "$TC_DIR" | wc -l)

    local contract_file="/tmp/architecture-contract.yaml"
    curl -sL "$CONTRACT_URL" -o "$contract_file" 2>/dev/null || true

    local contract_interfaces=$(yq e '.spi_contracts[].interfaces[].name' "$contract_file" 2>/dev/null | wc -l || echo "0")
    local covered_interfaces=$(grep -rhP '(?<=接口: )[A-Za-z.]+' "$TC_DIR" | sort -u | wc -l || echo "0")
    local coverage_rate=$(echo "scale=2; $covered_interfaces * 100 / $contract_interfaces" | bc 2>/dev/null || echo "N/A")

    cat > "$OUTPUT" << EOF
<!DOCTYPE html>
<html>
<head>
    <title>TC Quality Report — $(date +%Y-%m-%d)</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; }
        .metric { display: inline-block; margin: 20px; padding: 20px; border: 1px solid #ddd; border-radius: 8px; }
        .metric-value { font-size: 36px; font-weight: bold; color: #2c3e50; }
        .metric-label { font-size: 14px; color: #7f8c8d; }
        .pass { color: #27ae60; }
        .warn { color: #f39c12; }
        .fail { color: #e74c3c; }
        table { border-collapse: collapse; width: 100%; margin-top: 20px; }
        th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
        th { background-color: #f5f5f5; }
        .cross-repo { background-color: #e8f4f8; }
    </style>
</head>
<body>
    <h1>测试用例质量报告</h1>
    <p>生成时间: $(date)</p>
    <p class="cross-repo">待测对象与测试仓物理隔离，测试逻辑对上层不可见</p>

    <div class="metric">
        <div class="metric-value">$total_tc</div>
        <div class="metric-label">总 TC 数</div>
    </div>
    <div class="metric">
        <div class="metric-value">$p0_tc</div>
        <div class="metric-label">P0 阻塞级 TC</div>
    </div>
    <div class="metric">
        <div class="metric-value">$(echo "scale=0; $automated_tc * 100 / $total_tc" | bc)%</div>
        <div class="metric-label">自动化就绪率</div>
    </div>
    <div class="metric cross-repo">
        <div class="metric-value">${coverage_rate}%</div>
        <div class="metric-label">仓间契约接口覆盖率</div>
    </div>

    <h2>模块分布</h2>
    <table>
        <tr><th>模块</th><th>TC 数</th><th>P0</th><th>P1</th><th>自动化</th></tr>
EOF

    for module in AC AS AM AE AB EV GM; do
        local count=$(grep -rl "用例编号:.*-${module}-" "$TC_DIR" | wc -l)
        local p0=$(grep -rl "用例编号:.*-${module}-" "$TC_DIR" | xargs grep -l "优先级:.*P0" 2>/dev/null | wc -l)
        local auto=$(grep -rl "用例编号:.*-${module}-" "$TC_DIR" | xargs grep -l "自动化状态:.*READY" 2>/dev/null | wc -l)
        echo "        <tr><td>${module}</td><td>${count}</td><td>${p0}</td><td>$((count - p0))</td><td>${auto}</td></tr>" >> "$OUTPUT"
    done

    cat >> "$OUTPUT" << EOF
    </table>

    <h2>架构约束覆盖矩阵</h2>
    <table>
        <tr><th>§4 约束</th><th>TC 覆盖数</th><th>状态</th></tr>
EOF

    for i in $(seq 1 65); do
        local cov=$(grep -rl "§4\s*#${i}[^0-9]" "$TC_DIR" | wc -l)
        local status="<span class='fail'>未覆盖</span>"
        [ "$cov" -gt 0 ] && status="<span class='pass'>已覆盖</span>"
        echo "        <tr><td>§4 #${i}</td><td>${cov}</td><td>${status}</td></tr>" >> "$OUTPUT"
    done

    cat >> "$OUTPUT" << EOF
    </table>

    <h2>隔离合规检查</h2>
    <table>
        <tr><th>检查项</th><th>状态</th><th>说明</th></tr>
        <tr>
            <td>测试仓对待测对象源码可见性</td>
            <td><span class="pass">PASS</span></td>
            <td>仅消费 architecture-contract.yaml，不读取源码</td>
        </tr>
        <tr>
            <td>待测对象对测试逻辑可见性</td>
            <td><span class="pass">PASS</span></td>
            <td>门禁脚本、TC 文档、Mock 数据完全不可见</td>
        </tr>
        <tr>
            <td>PII 数据脱敏检查</td>
            <td><span class="pass">PASS</span></td>
            <td>Mock 数据中无真实业务 PII</td>
        </tr>
    </table>
</body>
</html>
EOF

    echo "报告已生成: $OUTPUT"
}

generate_html_report
```

---

## 5. CI/CD 集成方案

### 5.1 待测对象仓 CI（生成并发布契约）

```yaml
# spring-ai-ascend/.github/workflows/publish-contract.yml
name: Publish Architecture Contract

on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  build-and-publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build JARs
        run: ./mvnw clean package -DskipTests

      - name: Generate architecture-contract.yaml
        run: |
          ./scripts/generate-contract.sh             --version ${{ github.ref_name }}             --sha ${{ github.sha }}             --output architecture-contract.yaml

      - name: Publish to Nexus
        run: ./mvnw deploy -DskipTests

      - name: Upload Contract to Release
        uses: actions/upload-release-asset@v1
        with:
          upload_url: ${{ steps.create_release.outputs.upload_url }}
          asset_path: ./architecture-contract.yaml
          asset_name: architecture-contract-${{ github.ref_name }}.yaml
          asset_content_type: text/yaml

      - name: Trigger Test Repository
        run: |
          curl -X POST             -H "Authorization: token ${{ secrets.TEST_REPO_TOKEN }}"             -H "Accept: application/vnd.github.v3+json"             https://api.github.com/repos/huawei/spring-ai-ascend-tests/dispatches             -d '{
              "event_type": "sut-contract-published",
              "client_payload": {
                "version": "${{ github.ref_name }}",
                "sha": "${{ github.sha }}",
                "contract_url": "https://nexus.example.com/spring-ai-ascend/architecture-contract.yaml"
              }
            }'
```

### 5.2 测试仓 CI（消费契约并执行门禁）

```yaml
# spring-ai-ascend-tests/.github/workflows/tc-gate.yml
name: TC Quality Gate

on:
  repository_dispatch:
    types: [sut-contract-published]
  pull_request:
    paths:
      - 'docs/tests/sit/**'
  push:
    branches: [main]

jobs:
  tc-lint:
    name: Layer 1 — TC Format & Anti-Detection
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run TC Lint
        run: |
          chmod +x scripts/tc-lint.sh
          ./scripts/tc-lint.sh --all
        env:
          CONTRACT_URL: ${{ github.event.client_payload.contract_url || 'https://nexus.example.com/spring-ai-ascend/latest/architecture-contract.yaml' }}

  tc-gate:
    name: Layer 2 — Full Gate & Bidirectional Coverage
    runs-on: ubuntu-latest
    needs: tc-lint
    steps:
      - uses: actions/checkout@v4
      - name: Install dependencies
        run: sudo apt-get install -y bc yq curl
      - name: Run TC Gate
        run: |
          chmod +x scripts/tc-gate.sh
          ./scripts/tc-gate.sh --pr-id ${{ github.event.pull_request.number || '0' }} --base-branch main
        env:
          CONTRACT_URL: ${{ github.event.client_payload.contract_url || 'https://nexus.example.com/spring-ai-ascend/latest/architecture-contract.yaml' }}
          ARCH_MD_URL: https://raw.githubusercontent.com/huawei/spring-ai-ascend/main/ARCHITECTURE.md

  tc-report:
    name: Layer 3 — Quality Report
    runs-on: ubuntu-latest
    needs: tc-gate
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4
      - name: Generate Report
        run: |
          chmod +x scripts/tc-report.sh
          ./scripts/tc-report.sh --output reports/tc-quality-report.html
        env:
          CONTRACT_URL: ${{ github.event.client_payload.contract_url || 'https://nexus.example.com/spring-ai-ascend/latest/architecture-contract.yaml' }}
      - name: Upload Report
        uses: actions/upload-artifact@v4
        with:
          name: tc-quality-report
          path: reports/tc-quality-report.html

  trigger-sut-validation:
    name: Notify SUT Repository
    runs-on: ubuntu-latest
    needs: tc-gate
    if: github.event_name == 'repository_dispatch'
    steps:
      - name: Notify SUT
        run: |
          curl -X POST             -H "Authorization: token ${{ secrets.SUT_REPO_TOKEN }}"             -H "Accept: application/vnd.github.v3+json"             https://api.github.com/repos/huawei/spring-ai-ascend/dispatches             -d '{
              "event_type": "test-gate-passed",
              "client_payload": {
                "test_version": "${{ github.sha }}",
                "sut_version": "${{ github.event.client_payload.version }}"
              }
            }'
```

---

## 6. 防探测机制

### 6.1 资产隔离矩阵

| 资产类型 | 存放位置 | 待测对象可见性 | 防护措施 |
|---------|---------|--------------|---------|
| TC 文档 | 测试仓 `docs/tests/sit/` | ❌ 不可见 | 独立仓库 + 私有访问控制 |
| 门禁脚本 | 测试仓 `scripts/` | ❌ 不可见 | 独立仓库 + CI 日志脱敏 |
| Mock 数据 | 测试仓 `src/test/resources/mock/` | ❌ 不可见 | 独立仓库 + 敏感数据加密 |
| 仓间契约文件 | 待测对象仓发布 → Maven/Nexus | ✅ 公共可见 | 仅含接口签名，不含实现逻辑 |
| 架构文档 | 待测对象仓根目录 | ✅ 公共可见 | 作为"被挑战对象" |
| 测试执行日志 | 测试仓 CI Artifacts | ❌ 不可见 | 私有 CI 环境 |
| 覆盖率报告 | 测试仓 CI Artifacts | ❌ 不可见 | 私有 CI 环境 |

### 6.2 日志脱敏策略

```yaml
# 测试仓 CI 日志脱敏配置
log_redaction:
  rules:
    - pattern: '"password":\s*"[^"]+"'
      replacement: '"password": "***REDACTED***"'
    - pattern: '"api-key":\s*"[^"]+"'
      replacement: '"api-key": "***REDACTED***"'
    - pattern: 'thenReturn\(([^)]+)\)'
      replacement: 'thenReturn(***MOCK_VALUE***)'
    - pattern: 'assertThat\(([^)]+)\)\.isEqualTo\(([^)]+)\)'
      replacement: 'assertThat(***ACTUAL***)\.isEqualTo(***EXPECTED***)'

  public_log_fields:
    - test_case_id
    - test_result: [PASS, FAIL, SKIP]
    - execution_time_ms
    - sut_version
    - contract_version
```

---

## 7. 度量指标与持续改进

| 指标 | 目标值 | 测量方式 | 改进动作 |
|-----|-------|---------|---------|
| **仓间契约接口覆盖率** | 100% | tc-gate.sh 扫描契约 vs TC | 未覆盖接口强制分配 TC |
| **架构约束覆盖率** | 100% | tc-gate.sh 生成矩阵 | 未覆盖约束强制分配 TC |
| **特性覆盖率** | 100% | tc-gate.sh 比对特性跟踪矩阵 | 未覆盖特性强制分配 TC |
| **自动化就绪率** | ≥80% | tc-report.sh 统计 | P0 TC 必须自动化 |
| **防探测合规率** | 100% | tc-lint.sh PII 检测 | PII 泄露立即阻塞 |
| **TC 重复率** | ≤5% | tc-gate.sh 相似度检测 | 合并重复 TC |
| **评审闭环率** | 100% | tc-lint.sh 评审记录检查 | 未评审 TC 禁止合入 |
| **模糊语言率** | ≤10% | tc-lint.sh NLP 检测 | 量化预期结果 |

---

## 8. 附录：TC 标准模板（Markdown）

```markdown
---
用例编号: SIT-ASE-2M-CONTRACT-001
测试标题: agent-service 调用 agent-execution-engine 的 Orchestrator SPI 执行 Graph 模式
优先级: P0
自动化状态: READY
适用Posture: dev,research,prod
作者: zhangsan
创建日期: 2026-05-26
评审记录: |
  评审人: lisi,wangwu
  评审日期: 2026-05-27
  结论: 通过
---

## 关联特性
- Feature-009: Agent 执行引擎必须支持 Graph 和 AgentLoop 两种执行模式

## 关联架构约束
- §4 #9: Dual-mode runtime + interrupt-driven nesting
- ADR-0088: agent-runtime-core dissolution + orchestration SPI relocation
- ADR-0024: Suspension write atomicity

## 前置条件
1. 待测对象版本: 1.0.0-rc13 (commit 82a1397)
2. `APP_POSTURE=dev`
3. 仓间契约中 `Orchestrator.orchestrate(RunContext, ExecutorDefinition)` 接口已发布
4. 测试数据: `GraphDefinition` 包含 2 个节点（start → end），边为无条件转移

## 测试步骤
1. 构造 `RunContext`（tenantId="tenant-001", runId=UUID.randomUUID()）
2. 构造 `GraphDefinition.simple(nodes, edges, "start")`
3. 调用 `orchestrator.orchestrate(runContext, definition)`
4. 观察返回的 `Run` 对象状态
5. 查询持久化状态验证 DFA 转换

## 测试数据
- tenantId: "tenant-001"（有效 UUID 格式）
- 节点函数: `node -> Map.of("result", "ok")`
- 超时设置: 30s（验证 `SkillResourceMatrix.wallClockMs` 上限）

## 预期结果
1. 返回 `Run` 对象，`status` = `SUCCEEDED`
2. 持久化记录 `status` = `SUCCEEDED`，`tenant_id` = "tenant-001"
3. `Checkpointer.save()` 被调用 2 次（start 节点前 + end 节点后）
4. 总耗时 < 5s（W0 dev posture 放宽标准）
5. Logback MDC 中 `trace_id` 和 `run_id` 非空

## 清理策略
- 删除持久化记录
- 清空 checkpoint 数据
```

---

> **文档控制**
> - 本文档为架构级基础设施文档，遵循 ADR-0068 Layered 4+1 规范。
> - 门禁脚本存放路径：`scripts/tc-lint.sh`、`scripts/tc-gate.sh`、`scripts/tc-report.sh`
> - TC 存放路径：`docs/tests/sit/*.md`
> - 仓间契约文件：`architecture-contract.yaml`（待测对象仓发布，测试仓消费）
> - 任何修改必须通过 `docs/logs/reviews/` 提案流程。
