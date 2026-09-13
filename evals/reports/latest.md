# 泡泡 Agent 评测报告

> 运行时间：2026-09-13 22:20:26　|　模式：**replay**　|　模型：`deepseek-v4-flash`

## 总体指标

| 指标 | 值 | 含义 |
|---|---|---|
| 任务完成率 | **100.0%** | 工具、参数、回答全部达标（33/33） |
| 工具选择正确率 | 100.0% | 工具序列与期望完全一致，且没调用被禁止的工具 |
| 参数正确率 | 100.0% | 声明的参数断言全部通过 |
| 平均轮次 | 2.00 | 每个任务平均请求模型几次 |
| 平均估算 token | 1369 | 输入上下文 + 工具 schema，2 字符/token 口径 |

## 分类表现

| 类别 | 通过 | 完成率 | 工具选择 |
|---|---|---|---|
| memory | 6/6 | 100.0% | 100.0% |
| multi | 8/8 | 100.0% | 100.0% |
| note | 6/6 | 100.0% | 100.0% |
| reminder | 8/8 | 100.0% | 100.0% |
| time | 5/5 | 100.0% | 100.0% |

## 失败归因表（0 条）

本轮全部通过。

## 全部用例

| 用例 | 类别 | 期望工具 | 实际工具 | 轮次 | 结果 |
|---|---|---|---|---|---|
| mem-01 | memory | remember_fact | remember_fact | 2 | ✅ |
| mem-02 | memory | remember_fact | remember_fact | 2 | ✅ |
| mem-03 | memory | remember_fact | remember_fact | 2 | ✅ |
| mem-04 | memory | remember_fact,create_note | remember_fact,create_note | 3 | ✅ |
| mem-05 | memory |  | — | 1 | ✅ |
| mem-06 | memory |  | — | 1 | ✅ |
| multi-01 | multi | create_reminder,create_note | create_reminder,create_note | 3 | ✅ |
| multi-02 | multi | get_time,create_reminder | get_time,create_reminder | 3 | ✅ |
| multi-03 | multi | remember_fact,get_time | remember_fact,get_time | 3 | ✅ |
| multi-04 | multi | get_time,remember_fact,create_reminder | get_time,remember_fact,create_reminder | 4 | ✅ |
| multi-05 | multi |  | — | 1 | ✅ |
| multi-06 | multi | create_note | create_note | 2 | ✅ |
| multi-07 | multi |  | — | 1 | ✅ |
| multi-08 | multi | get_time,create_reminder | get_time,create_reminder | 3 | ✅ |
| note-01 | note | create_note | create_note | 2 | ✅ |
| note-02 | note | create_note | create_note | 2 | ✅ |
| note-03 | note | create_note | create_note | 2 | ✅ |
| note-04 | note | get_time,create_note | get_time,create_note | 3 | ✅ |
| note-05 | note |  | — | 1 | ✅ |
| note-06 | note |  | — | 1 | ✅ |
| rem-01 | reminder | create_reminder | create_reminder | 2 | ✅ |
| rem-02 | reminder | create_reminder | create_reminder | 2 | ✅ |
| rem-03 | reminder | create_reminder | create_reminder | 2 | ✅ |
| rem-04 | reminder | create_reminder | create_reminder | 2 | ✅ |
| rem-05 | reminder | create_reminder | create_reminder | 2 | ✅ |
| rem-06 | reminder |  | — | 1 | ✅ |
| rem-07 | reminder | create_note | create_note | 2 | ✅ |
| rem-08 | reminder | create_reminder,create_reminder | create_reminder,create_reminder | 3 | ✅ |
| time-01 | time | get_time | get_time | 2 | ✅ |
| time-02 | time | get_time | get_time | 2 | ✅ |
| time-03 | time | get_time | get_time | 2 | ✅ |
| time-04 | time |  | — | 1 | ✅ |
| time-05 | time |  | — | 1 | ✅ |

