// Allure 3 report config — auto-discovered by `allure generate` / `allure serve` via the
// allurerc.* filename convention, so a bare `allure serve target/allure-results` one-shots the
// reports below with no -c flag.
//
// Layout: multi-instance plugin-awesome (official Allure v3 pattern). Each key under `plugins`
// that imports "@allurereport/plugin-awesome" is a SEPARATE instance, producing its own namespaced
// subreport under <output>/<key>/ plus a root index.html multi-report switcher that lists every
// instance by its reportName. So we get several grouped views in one serve, each with its own
// groupBy, without collision.
//
// The two instances we keep (a third, awesome-suites, was dropped — see below):
//   • awesome-behaviors — groupBy epic/feature/story. THE useful one: our @Feature/@Story labels
//     flow straight into a feature → story tree (FEAT-001..004 → 22 stories).
//   • awesome-packages — groupBy package/class/method. A flat per-test-class index (JUnit 5 sets
//     package = the FQCN and emits no class/method labels, so this renders one node per class).
//
// Why no awesome-suites: under JUnit 5 parentSuite/suite/subSuite are not populated distinctly
// from package, so a groupBy:["parentSuite","suite","subSuite"] instance renders a tree that is
// BYTE-IDENTICAL to awesome-packages (verified via `cmp` on the two tree.json). Keeping it only
// added a redundant entry to the switcher. Drop it until @Suite annotations are added.
//
// history: historyPath makes historyLimit actually take effect (otherwise historyLimit is a no-op,
// per the v3 docs) and unlocks the trend / flaky / status-transition / stability charts that need
// cross-run data. The first run only seeds the file; charts populate from the 2nd run onward.
// (Path lives under target/ which `mvn clean` wipes — move it outside target/ if you want trends
// to survive a clean.)
//
// categories: Allure 3 requires { rules: [ { name, matchers: {...}, ... } ] } and `matchers` is
// MANDATORY. The object-matcher keys are statuses / message / trace / labels / flaky / transitions
// — NOT the Allure-2 names matchedStatuses / messageRegex / traceRegex. The Allure-2 flat fields
// are silently ignored in v3, so an array of {name, matchedStatuses, messageRegex} rules renders
// NOTHING (only the two built-in Product-errors/Test-errors defaults appear). That was the prior
// state of this block; it is now the v3 shape. (flaky + transitions need historyPath to match.)
//
// NOTE: no `import { defineConfig } from "allure"`. That helper is a type-only passthrough with no
// runtime effect, and the global `allure` package is not resolvable as a bare specifier from the
// project root (no local node_modules). A plain default-exported object is what Allure 3's config
// loader consumes, so we keep it dependency-free and portable.

export default {
  // Report title (Allure 3 field; rendered in each subreport header).
  name: "自动化测试报告",

  // Where `allure generate` writes the HTML report(s) + the root multi-report switcher.
  output: "./target/allure-report",

  // Cross-run history (see header comment: needed for trend/flaky/stability charts and for
  // historyLimit to take effect).
  // historyPath: "./target/allure-history.jsonl",
  historyLimit: 20,

  plugins: {
    // 实例1：Feature/Story 行为视图（BDD 层级）— 主视图
    "awesome-behaviors": {
      import: "@allurereport/plugin-awesome",
      options: {
        reportName: "功能模块视图",
        reportLanguage: "zh",
        singleFile: false,
        groupBy: ["epic", "feature", "story"],
      },
    },

    // 实例2：Package 代码结构视图（按测试类索引）
    "awesome-packages": {
      import: "@allurereport/plugin-awesome",
      options: {
        reportName: "包结构视图",
        reportLanguage: "zh",
        singleFile: false,
        groupBy: ["package", "class", "method"],
      },
    },
  },

  // Defect categories, v3 shape: { rules: [ { name, matchers: {...} } ] }. matchers is mandatory;
  // keys are statuses / message / trace / labels / flaky (NOT matchedStatuses / messageRegex /
  // traceRegex). Custom rules win first; unmatched failed/broken tests fall back to the built-in
  // Product-errors / Test-errors defaults. Zero-match categories are hidden automatically.
  categories: {
    rules: [
      {
        name: "产品缺陷",
        matchers: {
          statuses: ["failed"],
          message: ".*(AssertionError|Expected|but was).*",
          trace: ".*(java\\.lang\\.AssertionError).*",
        },
      },
      {
        name: "测试脚本缺陷",
        matchers: {
          statuses: ["broken"],
          message: ".*(NullPointer|NoSuchElement|Timeout|StaleElement).*",
        },
      },
      {
        name: "环境问题",
        matchers: {
          statuses: ["broken", "failed"],
          message: ".*(Connection refused|SocketTimeout|503|502).*",
        },
      },
      {
        name: "不稳定用例",
        matchers: { flaky: true, statuses: ["failed"] }, // flaky needs historyPath
      },
    ],
  },
};
