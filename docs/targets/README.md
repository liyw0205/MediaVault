# 当前目标窗口

本目录使用固定五项目标窗口管理 MediaVault 1.0.x 后续开发。每个目标均需完成实现、自动测试、APK 构建、文档状态更新、中文提交及推送；目标完成后补充新的第五项，始终保持五个有序目标。

| 顺序 | 文件 | 状态 | 目标 |
| --- | --- | --- | --- |
| 01 | `01_detail_remove_and_source_context.md` | 已完成（1.0.8） | 详情页安全移出库与来源上下文 |
| 02 | `02_task_structured_statistics.md` | 已完成（1.0.9） | 任务中心扫描与刮削结构化统计 |
| 03 | `03_task_retry_explanation.md` | 已完成（1.0.10） | 可解释失败重试与最近错误 |
| 04 | `04_remote_mapping_migration_preview.md` | 已完成（1.0.11） | 远程源映射变更影响预览 |
| 05 | `05_remote_mapping_migration_apply.md` | 已完成（1.0.12） | 远程源映射安全应用与复检 |
| 06 | `06_task_scope_retry_replay.md` | 已完成（1.0.13） | 任务原始范围安全重试 |
| 07 | `07_task_retry_scope_validation.md` | 已完成（1.0.14） | 任务重试前范围有效性校验 |

约束：不删除用户源文件，不清空远程配置，不导出明文密码；涉及移除、迁移或批量修复时必须先展示影响范围，并提供可理解的结果反馈。
