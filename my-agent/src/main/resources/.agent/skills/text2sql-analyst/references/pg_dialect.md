# PostgreSQL 方言速查（问数 SQL 修正参考）

execute_sql 报语法/类型错误时，按本表修正。

## 日期与时间

| 需求 | 写法 |
|---|---|
| 日期字面量 | `DATE '2026-09-01'` 或 `'2026-09-01'::date` |
| 今天 | `CURRENT_DATE` |
| 本月第一天 | `date_trunc('month', CURRENT_DATE)` |
| 上月范围 | `order_date >= date_trunc('month', CURRENT_DATE) - interval '1 month' AND order_date < date_trunc('month', CURRENT_DATE)` |
| 近30天 | `order_date >= CURRENT_DATE - interval '29 days'` |
| 按月分组 | `GROUP BY date_trunc('month', order_date)` |
| 提取年/月 | `EXTRACT(YEAR FROM order_date)` / `EXTRACT(MONTH FROM order_date)` |

## 常见错误修正

| 错误信息关键词 | 原因 | 修正 |
|---|---|---|
| `division by zero` | 分母为0 | `x / NULLIF(y, 0)` |
| `integer division` 结果恒为0 | int/int 截断 | `x::numeric / y` |
| `column ... does not exist` | 列名猜错或大小写 | 核对 retrieve_schema 返回；PG 默认小写 |
| `ambiguous column` | 多表同名列 | `表名.列名` 限定 |
| `syntax error at or near "'`" | 双引号字符串 | PG 中字符串用单引号，双引号是标识符 |
| `function date_format does not exist` | MySQL 函数 | 用 `to_char(order_date, 'YYYY-MM')` |
| `function ifnull does not exist` | MySQL 函数 | 用 `COALESCE(x, 0)` |
| `LIMIT` 报错 | SQL Server 语法 | PG 用 `LIMIT n`，不支持 `TOP` |

## 聚合与窗口

- 占比：`x::numeric / NULLIF(SUM(x) OVER (), 0)`（窗口总和做分母）
- 排名：`RANK() OVER (ORDER BY gmv DESC)` 或 `ROW_NUMBER()`
- 同比：分别查两个周期后在回答层计算，或用 `LAG() OVER (ORDER BY 月份)`
- 条件聚合：`COUNT(*) FILTER (WHERE order_status IN (1,2,3))`（PG 特有，比 CASE WHEN 简洁）

## 强制习惯

1. 任何大表查询必须带时间分区字段过滤。
2. 明细查询必须 LIMIT（系统会强制追加，但主动写更小值更省 token）。
3. 结果列起中文别名提升可读性：`SUM(gmv) AS "销售额(元)"`。
