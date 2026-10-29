const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const zhTw = require(path.join(__dirname, "../src/main/resources/marketweb/zh-tw.js"));
const SOURCES = ["app.js", "map.js", "logistics-render.js", "logistics-panel.js", "tellus-basemap.js"]
  .map((name) => path.join(__dirname, "../src/main/resources/marketweb", name));
const STRING_LITERAL = /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`/g;

test("convertsToTaiwanTraditionalWithTaiwanWording", () => {
  assert.equal(zhTw.convert("网页市场"), "網頁市場");
  assert.equal(zhTw.convert("设置"), "設定");
  assert.equal(zhTw.convert("账户"), "帳戶");
  assert.equal(zhTw.convert("管理权限"), "管理權限");
  assert.equal(zhTw.convert("市场列表已刷新"), "市場列表已重新整理");
  assert.equal(zhTw.convert("发布求购"), "發布求購");
  assert.equal(zhTw.convert("订单类型"), "訂單類型");
  assert.equal(zhTw.convert("已加载 {count} 个区块"), "已載入 {count} 個區塊");
});

test("leavesNonChineseTextAlone", () => {
  assert.equal(zhTw.convert("Order #42 {name}"), "Order #42 {name}");
  assert.equal(zhTw.convert(""), "");
  assert.equal(zhTw.convert(null), "");
});

test("everyChineseCharacterInTheWebPageStringsIsInTheTable", () => {
  const covered = new Set(zhTw.COVERED);
  const missing = new Set();
  for (const file of SOURCES) {
    for (const literal of fs.readFileSync(file, "utf8").match(STRING_LITERAL) || []) {
      for (const ch of literal.match(/[\u4e00-\u9fff]/g) || []) {
        if (!covered.has(ch)) missing.add(ch);
      }
    }
  }
  assert.deepEqual([...missing], [], "regenerate with: python3 tools/gen_zh_tw.py");
});
