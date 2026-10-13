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

## 二、前端多端方案（PC + 移动浏览器）

单代码库自适应，**不做**独立移动站/两套前端。Admin 后台明确 PC-only；只有 C 端（LearnLayout 五页 + Share）做移动适配。

- **断点**：`1024px` 一刀切分 PC/移动。CSS 移动优先书写：默认即移动样式，`@media (min-width: 1024px)` 内做桌面增强。
- **导航双形态**：`frontend/src/layouts/LearnLayout.vue` 的 `tabs` 数组是唯一数据源，同一 DOM 靠 grid-template-areas 物化——≥1024px 渲染为深色左侧栏（应用壳：侧栏/头部固定、内容内滚动），小于则渲染为底部 tab 栏。改导航只改 `tabs` 与样式，勿拆成两套 DOM。
- **移动端硬规则**：
  - hover 样式必须包在 `@media (hover: hover)` 内（触屏无 hover，否则点击粘色）；
  - viewport 已开 `viewport-fit=cover`，贴屏幕底部的粘性/固定元素要加 `padding-bottom: env(safe-area-inset-bottom)`；
  - 禁双击缩放、去点击高亮、横向溢出兜底已在 `main.css` 全局处理，页面内勿重复；
  - 弹窗类组件（el-dialog 等）小屏应绑 `:fullscreen`。
- **交互分叉**：某页 PC/移动最优交互根本不同（如模拟考键盘作答 vs 触控）时，用 `matchMedia('(max-width: 767px)')` 的 composable + `v-if` 在页内做双布局；重组件勿用 CSS 隐藏（DOM 仍会渲染）。
- **弹窗/浮层高度自适应（跨屏一致，硬规则）**：
  - 高度一律 `calc(100vh - 固定开销)`（如 `calc(100vh - 200px)`，开销 = top + header + footer + 余量）；**禁止**「比例 + 像素上限」混搭（`min(84vh, 880px)` 这类）——像素上限在特定档位屏幕封顶（2K 调好、1080p/4K 走样），比例值又让固定开销随屏漂移；
  - `top` 用固定 px（如 `top="24px"`）不用 vh/vw，否则 calc 公式的固定项不再固定；
  - 内容超高走弹窗内部滚动，不撑弹窗外框；宽同理用 `min(Xpx, 9Xvw)` 封顶可以，高度不行（高度要"到底"，宽度要"限宽"，诉求不同）；
  - 分步弹窗（向导类）：固定高度舞台 + 步骤间带方向过渡（前进左出、后退右入），外框纹丝不动，各步内容 flex 自适应画布——切换才有"同一界面翻页"感，而不是多个页面跳变。参考实现：`frontend/src/components/kb/WizardDialog.vue`。
- **AI 按钮统一识别（硬规则）**：所有触发 AI/模型调用的按钮一律加全局 `.btn-ai` 类（墨青渐变实底，亮暗两套定义在 `theme.css` 末尾），不再单独配色；按钮在 busy 时切红色「停止」态的，用 `:class="{ 'btn-ai': !busy }"` 动态移除避免覆盖 danger。
- **新页面验收**：PC 全宽 + DevTools 设备模拟（iPhone SE / 15 Pro Max）各过一遍；窗口拖过 1024px 确认导航形态切换正常。