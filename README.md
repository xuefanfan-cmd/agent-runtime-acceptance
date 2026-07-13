# agent-runtime-acceptance

System Integration Test (SIT) framework for the **spring-ai-ascend** agent
runtime. The suite launches one or more spring-ai-ascend agents as black-box
`java -jar` processes, drives them through their A2A endpoints, and asserts
the resulting behaviour at four scopes: component, integration, end-to-end,
and performance.

## Tech stack

- Java 21 + Spring Boot 4.0.5
- A2A Protocol SDK (`org.a2aproject.sdk:a2a-java-sdk-client`, JSON-RPC transport)
- JUnit 5 + AssertJ
- Awaitility for async state polling
- Testcontainers (reserved for future scenarios)

## Layout

```
docs/
  cases/                                       # Per-case design docs (A-NN)
  spring-ai-ascend-integration-test-design.md
  spring-ai-ascend-tc-quality-gate-design.md
src/
  main/java/com/huawei/ascend/sit/
    lifecycle/                                 # SutStack / ProcessLauncher / AgentConfig
    client/                                    # A2A SDK wrapper + scenario executor
    config/                                    # TestConfig / TestEnvironment
    utils/                                     # JsonUtils / WaitUtils / AuthUtils / ...
  test/java/com/huawei/ascend/sit/
    base/                                      # Base{Component,Integration,E2E,ManagedStack}Test
    cases/component/                           # @Tag("component")
    cases/integration/                         # @Tag("integration")
    cases/e2e/                                 # @Tag("e2e")
    cases/performance/                         # @Tag("performance")
    suites/                                    # Smoke / E2E / Performance / Regression
  test/resources/
    application-{local,sit,uat}.yml
    testdata/                                  # Externalised inputs / contracts
```

## How a case runs

`SutStack` launches the declared agents **leaf-first**, allocating a random
free port per agent and wiring each upstream's
`agent-runtime.remote-agents[0].url` to the downstream's resolved base URL.
Tests obtain an A2A client through `stack.client("<agent-name>")` and
assert externally observable behaviour (HTTP responses, agent card, A2A run
lifecycle, etc.).

Single-agent example (no chain, no LLM):

```java
SutStack stack = SutStack.builder(config)
        .agent("mainplan")
        .start();
AgentCard card = stack.client("mainplan").getAgentCard();
```

Full chain (auto-wires mainplan → trip → hotel):

```java
SutStack stack = SutStack.builder(config)
        .agent("hotel")
        .agent("trip",     a -> a.role(MIDDLE).downstream("hotel"))
        .agent("mainplan", a -> a.role(ENTRY).downstream("trip"))
        .start();
```

See [src/main/java/com/huawei/ascend/sit/lifecycle/SutStack.java](src/main/java/com/huawei/ascend/sit/lifecycle/SutStack.java)
for the top-level abstraction and
[docs/cases/reactagent/A-01-agent-card-discovery.md](docs/cases/reactagent/A-01-agent-card-discovery.md)
for a worked case example.

## Running tests

```bash
# Component + integration (Surefire default; e2e and performance excluded)
./mvnw test

# Smoke suite
./mvnw -Dtest=SmokeTestSuite test

# E2E (Failsafe, requires the configured profile to be reachable)
./mvnw verify
```

Profiles live in `src/test/resources/application-{local,sit,uat}.yml`.
Agent Maven coordinates are configured under
`sut.agents.<name>.{group,artifact,version}`; `ProcessLauncher` resolves the
jar from the local `~/.m2/repository` (override via `sut.m2.repo`).

## Authoring a new case

1. Add a design doc under `docs/cases/reactagent/A-NN-<slug>.md` following the format
   of [A-01](docs/cases/reactagent/A-01-agent-card-discovery.md).
2. Add the test under `src/test/java/com/huawei/ascend/sit/cases/<layer>/`,
   extending `BaseManagedStackTest` (the managed-stack base class that
   owns the SUT lifecycle via `SutStack`).
3. Tag the test with `@Tag("<layer>")` so it lands in the right Surefire /
   Failsafe selection.
4. Externalise inputs under `src/test/resources/testdata/<layer>/...`.
