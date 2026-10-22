# 调研报告模板细化说明

本文件是 research-report 技能的补充参考，仅在需要撰写报告正文时按需加载。

## HTML 报告模板骨架

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>{{主题}} 调研报告</title>
  <style>
    body { font-family: "PingFang SC", "Microsoft YaHei", sans-serif; max-width: 960px; margin: 0 auto; padding: 32px; color: #222; line-height: 1.7; }
    h1 { border-bottom: 3px solid #2f6fed; padding-bottom: 8px; }
    h2 { color: #2f6fed; margin-top: 36px; }
    table { border-collapse: collapse; width: 100%; margin: 16px 0; }
    th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
    th { background: #f0f5ff; }
    .meta { color: #888; font-size: 13px; }
    .risk { background: #fff7e6; border-left: 4px solid #fa8c16; padding: 12px; }
    .refs { font-size: 13px; color: #555; }
  </style>
</head>
<body>
  <h1>{{主题}} 调研报告</h1>
  <p class="meta">生成日期：{{date}} ｜ 调研范围：{{时间/地域}} ｜ 信息来源数：{{n}}</p>
  <h2>1. 执行摘要</h2>
  <ul><li>{{核心结论1}} [来源编号]</li></ul>
  <h2>2. 调研背景与范围</h2>
  <h2>3. 现状分析</h2>
  <h2>4. 对比分析</h2>
  <table><tr><th>维度</th><th>{{对象A}}</th><th>{{对象B}}</th></tr></table>
  <h2>5. 趋势判断</h2>
  <h2>6. 风险与不确定性</h2>
  <div class="risk">{{风险与本次调研局限}}</div>
  <h2>7. 结论与建议</h2>
  <h2 class="refs">8. 参考来源</h2>
  <ol class="refs"><li>{{站点}} - {{标题}} - {{时间}}</li></ol>
</body>
</html>
```

## 写作规范

1. **执行摘要**：每条结论必须包含"是什么 + 数据/证据 + 意味着什么"，不超过两行。
2. **对比表格**：维度必须对齐（同一行比较同一属性），空值写"未公开"而不是留空或编造。
3. **数字标注**：所有数字注明时间与来源编号；换算过的数字注明换算方式。
4. **趋势判断**：使用"预计/倾向于"等谨慎措辞，每条趋势后跟一句支撑证据。
5. **建议**：按 P0/P1/P2 优先级排序，每条建议可独立执行，注明预期收益与前提条件。
6. **Markdown 输出时**：结构同上，表格用 Markdown 表格语法，风险块用 `> ⚠️` 引用样式。

## 常见反模式（禁止出现）

- 通篇形容词没有数字（"发展迅速""前景广阔"）
- 引用来源与正文结论对不上
- 把搜索结果的原文大段复制当作分析
- 建议空洞不可执行（"加强创新""持续关注"）
