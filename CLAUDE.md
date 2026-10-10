# CLAUDE.md
---

## 一、常用命令

```bash
node start.js all        # 同时起前后端（起前端前会等后端就绪）
node start.js backend    # 只起后端
node start.js frontend   # 只起前端
node start.js build      # 编译后端（mvn clean package -DskipTests）
```

端口被占用时脚本会自动结束占用进程；`--no-kill` 可改为只报错。日志按 `[后端]` / `[前端]` 加前缀。

前后端手动命令：

```bash
mvn spring-boot:run              # 后端，8080
mvn clean compile                # 只编译（验证改动最快的方式）

cd frontend && npm run dev       # 前端，5173
cd frontend && npm run build     # 类型检查 + 构建（提交前务必跑）
cd frontend && npm run typecheck # 仅类型检查
```

**验证改动的最低要求**：

- 改 Java → `mvn clean compile`
- 改前端/TS → `cd frontend && npm run build`