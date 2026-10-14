/**
 * 后端契约的枚举与类型。
 *
 * 接口里的枚举多（subType / verdict / nextAction / levelTarget），
 * 用类型把后端契约固化到前端，能在编译期挡住字段拼写与取值错误。
 * 取值必须与后端 CHECK 约束、核心模块定义附录 A（枚举真相源）保持一致。
 */

/** 题型：type 单值知识点，差异下沉到 sub_type */
export type SubType = 'default' | 'design_chain' | 'narrative'

/** 知识点状态机：draft → reviewed → active → retired（retired 保留被合并指向） */
export type KpStatus = 'draft' | 'reviewed' | 'active' | 'retired'

/** 四档总评：优秀/良好/及格/不及格，由要点命中分布确定性算出，LLM 不碰分数 */
export type GradeLevel = 'excellent' | 'good' | 'pass' | 'fail'

/** 答况四分流：对/缺/错/空；"空"是独立值，不用空内容代替 */
export type Verdict = 'hit' | 'shallow' | 'wrong' | 'blank'

/** 判定驱动的下一动作：下钻/同层换角度/回退前置（内容层回退）/切题 */
export type NextAction = 'drill' | 'rephrase' | 'fallback' | 'switch'

/** 评分要点分级：基础/中级/资深（单层回答内要点深度，与职级四档是两套刻度） */
export type RubricLevel = 'basic' | 'mid' | 'senior'

/** 作答来源：打字或语音转写 */
export type AnswerSrc = 'text' | 'voice'

/** 后台人员角色：固定两值不做可配置角色（D18/D19） */
export type StaffRole = 'admin' | 'editor'

/** 账号状态：停用即对应侧会话失效并拒绝登录（D16/D18） */
export type AccountStatus = 'active' | 'disabled'

export interface CaptchaResp {
  captchaId: string
  svg: string
}

export interface CustomerAuthResp {
  token: string
  username: string
  customerId: number
}

export interface CustomerMeResp {
  id: number
  username: string
}

export interface StaffAuthResp {
  token: string
  username: string
  role: StaffRole
}

export interface StaffMeResp {
  id: number
  username: string
  role: StaffRole
}

export interface StaffAccountView {
  id: number
  username: string
  role: StaffRole
  status: AccountStatus
  createdAt: string
  lastLoginAt: string | null
}

/** 后端统一错误体（GlobalExceptionHandler 输出） */
export interface ApiError {
  status: number
  code: string
  message: string
  fields?: Record<string, string>
}

/** 分页响应统一契约：items 为当前页数据，total 为当前筛选条件下的总行数 */
export interface PageResult<T> {
  items: T[]
  total: number
}

/** LLM 调用入口（用途）：业务按用途调用，不认具体模型名（核心模块定义 4.2） */
export type LlmPurpose =
  | 'judge_practice'
  | 'judge_gacha'
  | 'judge_exam'
  | 'profile_draft'
  | 'narrative_draft'
  | 'exam_summary'
  | 'variant_rewrite'
  | 'split_fallback'
  | 'cluster_dedup'
  | 'ai_compose'
  | 'embedding'

/** 通用配置：下拉选项（value 随 JSON 透传，label 为显示名） */
export interface ConfigFieldOption {
  value: unknown
  label: string
}

/** 通用配置：条件可见性（op 取 eq/ne，field 引用同一行或同域内的兄弟字段） */
export interface ConfigVisibleWhen {
  field: string
  op: 'eq' | 'ne'
  value: unknown
}

/** 通用配置字段元数据：后端 ConfigSection.fields() 下发的渲染契约，前端据此自渲染 */
export interface ConfigFieldDef {
  key: string
  label: string
  type: 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'SWITCH' | 'SELECT' | 'DATE' | 'TAG' | 'TABLE'
  min?: number
  max?: number
  step?: number
  options?: ConfigFieldOption[]
  /** 动态选项数据源：域 sources 里的 key（与静态 options 二选一） */
  optionsSource?: string
  /** 选项依赖字段：optionsSource 为映射结构时，用该字段（域内或行内）的值作为取数 key */
  optionsDependsOn?: string
  creatable?: boolean
  /** 三态开关：SELECT 出现空值选项（Boolean 三态 null=跟随默认） */
  triState?: boolean
  nullLabel?: string
  tagText?: string
  visibleWhen?: ConfigVisibleWhen
  help?: string
  width?: number
  minWidth?: number
  /** TABLE 专用：固定行来源 "constants:<常量组>"；缺省为自由行（值是数组，可增删） */
  rowKeySource?: string
  columns?: ConfigFieldDef[]
}

/** 通用配置域视图：schema + 当前值 + 动态选项池一次下发（GET /api/admin/configs） */
export interface ConfigSectionView {
  key: string
  title: string
  description: string
  order: number
  fields: ConfigFieldDef[]
  value: Record<string, unknown>
  sources: Record<string, unknown>
}

export interface LlmConnectivityItem {
  purpose: string
  provider: string
  model: string
  embedding: boolean
  ok: boolean
  detail: string
}

export interface LlmConnectivityReport {
  allOk: boolean
  items: LlmConnectivityItem[]
}

/** C 端账号用量行：日/周/月三窗口计数 + 对应限额的超限标记 */
export interface LlmCustomerUsageRow {
  accountId: number
  username: string
  daily: number
  weekly: number
  monthly: number
  dailyExceeded: boolean
  weeklyExceeded: boolean
  monthlyExceeded: boolean
}

/** B 端入口用量行：该入口当日计数（入口细分仅用于观测，限流在总体口径） */
export interface LlmStaffUsageRow {
  purpose: string
  purposeName: string
  today: number
}

export interface LlmDailyPoint {
  date: string
  callCount: number
}

export interface LlmUsageView {
  date: string
  /** C 端按账号聚合（本月窗口） */
  customers: LlmCustomerUsageRow[]
  customerDailyLimit: number
  customerWeeklyLimit: number
  customerMonthlyLimit: number
  /** B 端按入口的当日明细 */
  staff: LlmStaffUsageRow[]
  staffTodayTotal: number
  staffDaily: number
  staffWeekly: number
  staffMonthly: number
  todayTotal: number
  siteDailyTotal: number
  circuitOpen: boolean
  recent7d: LlmDailyPoint[]
}

/**
 * 判定命中的要点快照：全量要点集（含未命中项），
 * 档位公式吃命中分布，内容校对后历史判定才可重算、可比（核心模块定义 4.1）。
 */
export interface VerdictHit {
  pointId: string
  layerKey: string
  level: RubricLevel
  pointText: string
  hit: boolean
  /** 引用强制：命中要点必须附用户原话，引不出算未命中；未命中为 null */
  quote: string | null
}

/** 判定输出契约（judge → 消费方），结构见核心模块定义附录 A 8.3 */
export interface JudgeResult {
  verdict: Verdict
  nextAction: NextAction
  hits: VerdictHit[]
  /** 教练口吻的一句话缺口描述 */
  missingSummary?: string
}

// —— 素材库（核心模块定义 5.1，B 端） ——

/** 素材状态机：待加工 → 已入库 / 已放弃（入库素材保留不删，红线 5） */
export type SourceStatus = 'pending' | 'submitted' | 'discarded'

/** 素材来源：手写 / 导入 / URL 抓取 / 反推出题 */
export type SourceType = 'manual' | 'import' | 'url' | 'inferred'

/** 内容难度四档（与职级要求正交，红线 6） */
export type SourceDifficulty = 'junior' | 'mid' | 'senior' | 'expert'

/** 领域代码：运行时由后端配置域 kb.domains 下发（系统设置页可增删改），此处不再固化字面量 */
export type DomainCode = string

export interface SourceItemView {
  id: number
  parentId: number | null
  title: string
  contentMd: string
  /** 概述齐备是提交入库的门槛 */
  summary: string | null
  /** 目标类型标记：kp 面试题线 / doc 文章线；初始导入可空，人工填写或采纳 AI 建议 */
  contentType: 'kp' | 'doc' | null
  difficulty: SourceDifficulty | null
  domainCode: string | null
  domainName: string | null
  topicName: string | null
  sourceType: SourceType
  sourceUrl: string | null
  status: SourceStatus
  submittedAt: string | null
  /** 历史拆分产出的子素材数（仅母素材有值；拆分功能已下线，仅存量数据展示用） */
  childCount: number
  createdAt: string
  updatedAt: string
}

export interface SourceCreateReq {
  title: string
  contentMd?: string
  sourceUrl?: string
  contentType?: string
}

export interface SourceUpdateReq {
  title?: string
  contentMd?: string
  summary?: string
  /** 传空串清空类型标记 */
  contentType?: string
  difficulty?: string
  domainCode?: string
  topicName?: string
  sourceUrl?: string
}

export interface SourceSubmitReq {
  domainCode: string
  /** 主题位置 id（T4 起主题以 kb_topic 表为准，AI 分类候选也从表取） */
  topicId: number
  /** 定类分流：面试题线 / 文章线（5.2 进门即分流） */
  contentType: KbContentType
  /** 追问链（仅面试题线消费；id 已在前端分配，入库即受红线 2 约束） */
  followups?: KbFollowup[]
}

/** 追问链草稿（AI 识别产物，无 id；提交时由前端补 id 与 status 成为 KbFollowup） */
export interface KbFollowupDraft {
  layer: number
  direction: string
  content: string
}

/** AI 改写产物：正文回编辑器 */
export interface SourceRewriteResp {
  content: string
}

/** AI 分类建议：仅领域内推荐主题位置，采纳或无视由人 */
export interface SourceClassifyResp {
  topicName: string
  reason: string
}

/** AI 定位：领域 + 现有主题或新主题名建议，全部经人工确认后生效 */
export interface SourceLocateResp {
  domainCode: string
  /** 现有主题名（该域内真实存在），空表示无现成主题 */
  topicName: string
  /** 新主题名建议（不落库，人工确认创建），与 topicName 互斥 */
  newTopic: string
  reason: string
}

/** 反推面试问法：锚点 + 问法 + 难度（初级/中级/高级/资深） */
export interface SourceInferItem {
  anchor: string
  question: string
  difficulty: string
}

export interface SourceInferResp {
  inferences: SourceInferItem[]
}

export interface SourceDomainTopicsResp {
  topics: string[]
}

/** 素材库分页响应：items/total 之外带全库 facets（状态分段计数与领域候选，不随筛选变化） */
export interface SourcePageView {
  items: SourceItemView[]
  total: number
  statusCounts: Record<string, number>
  domains: string[]
}

/** 素材库列表查询参数（page 从 1 起；status/domainCode/keyword 缺省为不过滤） */
export interface SourceListParams {
  page: number
  pageSize: number
  status?: string
  domainCode?: string
  keyword?: string
}

// —— 标准知识库（核心模块定义 5.2，B 端） ——

/** 标准内容状态机：draft(退回/恢复) → pending(待审) → available(上线)；deprecated(弃用) / merged(合并下线) */
export type KbStatus = 'draft' | 'pending' | 'available' | 'deprecated' | 'merged'

/** 定类分流：面试题线（知识点） / 文章线（资料讲解） */
export type KbContentType = 'kp' | 'doc'

/** 审核动作留痕：submit/resubmit/recheck 入队，pass/reject/discard 出队 */
export type KbReviewAction = 'submit' | 'resubmit' | 'recheck' | 'pass' | 'reject' | 'discard'

export interface KbTopicView {
  id: number
  domainCode: string
  name: string
  /** 覆盖范围描述：目录/下拉的人读提示，同时注入 AI 定位提示词（可空） */
  description: string | null
  status: 'active' | 'merged'
  mergedIntoId: number | null
  kpCount: number
  docCount: number
  createdAt: string
  updatedAt: string
}

/** 追问层：id 经分配永不复用、永不修改（红线 2），层的增减走新增/停用 */
export interface KbFollowup {
  id: string
  layer: number
  direction: string
  content: string
  status: 'active' | 'disabled'
}

/** 分级评分要点：判定引擎的对齐尺子（pass 及格 / good 良好 / excellent 优秀） */
export interface KbScoringPoint {
  id: string
  level: 'pass' | 'good' | 'excellent'
  content: string
}

export interface KbAnswerSample {
  level: 'pass' | 'good' | 'excellent'
  content: string
}

/** 职级要求与停止点：目标职级要不要会、下钻到第几层算够 */
export interface KbLevelRule {
  level: 'junior' | 'mid' | 'senior' | 'expert'
  required: boolean
  stopLayer: number | null
}

export interface KbVariant {
  text: string
}

/** 知识点（面试题线）：学习、判定、模拟考的主线实体 */
export interface KpView {
  id: number
  domainCode: string
  topicId: number
  topicName: string
  title: string
  summary: string | null
  frameworkMd: string
  variants: KbVariant[]
  followups: KbFollowup[]
  scoringPoints: KbScoringPoint[]
  answerSamples: KbAnswerSample[]
  difficulty: SourceDifficulty
  levelRules: KbLevelRule[]
  prerequisites: number[]
  freqManual: number
  freqSignal: number
  freqCoverage: number
  status: KbStatus
  mergedIntoId: number | null
  mergeSources: number[]
  reviewDueAt: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

/** 资料讲解（文章线）：无评分要求、无追问链，挂载知识点即发布 */
export interface DocView {
  id: number
  domainCode: string
  topicId: number
  topicName: string
  title: string
  summary: string | null
  contentMd: string
  difficulty: SourceDifficulty
  sourceUrl: string | null
  kpId: number | null
  status: KbStatus
  mergedIntoId: number | null
  mergeSources: number[]
  reviewDueAt: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

/** 目录/搜索/队列共用的内容摘要（目录与同位管理始终有可读摘要） */
export interface KbContentItemView {
  contentType: KbContentType
  id: number
  domainCode: string
  topicId: number
  topicName: string
  title: string
  summary: string | null
  difficulty: SourceDifficulty
  status: KbStatus
  /** 文章线挂载的知识点 */
  kpId: number | null
  mergedIntoId: number | null
  /** 合并产物（审核通过时源会标 merged 并转入覆盖度） */
  mergeProduct: boolean
  reviewDueAt: string | null
  createdAt: string
  updatedAt: string
}

export interface KbUpdateKpReq {
  title?: string
  summary?: string
  frameworkMd?: string
  variants?: KbVariant[]
  followups?: KbFollowup[]
  scoringPoints?: KbScoringPoint[]
  answerSamples?: KbAnswerSample[]
  difficulty?: SourceDifficulty
  levelRules?: KbLevelRule[]
  prerequisites?: number[]
  freqManual?: number
  topicId?: number
}

export interface KbUpdateDocReq {
  title?: string
  summary?: string
  contentMd?: string
  difficulty?: SourceDifficulty
  kpId?: number
  topicId?: number
}

/** 直达新建（不经素材库，概述与难度门槛一视同仁） */
export interface KbDirectSubmitReq {
  contentType: KbContentType
  domainCode: string
  topicId: number
  title: string
  summary: string
  contentMd?: string
  difficulty: SourceDifficulty
  /** 追问链（仅面试题线消费；id 已在前端分配，入库即受红线 2 约束） */
  followups?: KbFollowup[]
}

export interface KbReviewRecord {
  id: number
  contentType: KbContentType
  contentId: number
  action: KbReviewAction
  source: 'wizard' | 'direct' | 'merge' | 'recheck'
  checks: string | null
  comment: string | null
  operatorId: number
  createdAt: string
}

/** 四项强制校验：技术正确性/评分要点可判定性(kp)/侵权检查/内容质量；文章线 judgeable=na */
export interface KbReviewChecks {
  correctness: 'pass' | 'fail'
  judgeable: 'pass' | 'fail' | 'na'
  infringement: 'pass' | 'fail'
  quality: 'pass' | 'fail'
  notes?: string
}

export interface KbReviewDetail {
  contentType: KbContentType
  kp: KpView | null
  doc: DocView | null
  history: KbReviewRecord[]
}

/** AI 一键合并产物（不落库，回人手里；追问层/评分要点的 id 由服务端提交时分配） */
export interface KbMergeProduct {
  title: string
  summary: string
  frameworkMd?: string
  contentMd?: string
  variants?: KbVariant[]
  followups?: { layer: number; direction: string; content: string }[]
  scoringPoints?: { level: 'pass' | 'good' | 'excellent'; content: string }[]
  answerSamples?: KbAnswerSample[]
  difficulty: SourceDifficulty | ''
  levelRules?: KbLevelRule[]
  conflicts?: string[]
}

/** 知识点编辑草稿（前端编辑形态）：目录编辑抽屉与合并产物编辑共用 */
export interface KbKpDraft {
  title: string
  summary: string
  frameworkMd: string
  variants: KbVariant[]
  followups: KbFollowup[]
  scoringPoints: KbScoringPoint[]
  answerSamples: KbAnswerSample[]
  difficulty: SourceDifficulty
  levelRules: KbLevelRule[]
  prerequisites: number[]
  freqManual: number
}

/** 资料讲解编辑草稿 */
export interface KbDocDraft {
  title: string
  summary: string
  contentMd: string
  difficulty: SourceDifficulty
  kpId: number | null
}

// —— 判定引擎（核心模块定义 6.1，T6） ——

/** 答况四分流：对/缺/错/空；「空」是独立值。取值与后端 judge_verdict 常量组一致 */
export type JudgeVerdict = 'correct' | 'missing' | 'wrong' | 'empty'

/** 单知识点四档（6.1 硬规则 3：公式计算，模型不碰分） */
export type JudgeGrade = 'excellent' | 'good' | 'pass' | 'fail'

/** 判定目标职级四档（与 KbLevelRule.level 同口径） */
export type JudgeLevel = 'junior' | 'mid' | 'senior' | 'expert'

/** 判定来源：llm=引擎 / self=降级自评 / code=空答短路 */
export type JudgeSource = 'llm' | 'self' | 'code'

/** 评分要点全量快照（含未命中项）：内容改版后历史档位可重算 */
export interface JudgePointResult {
  id: string
  level: KbScoringPoint['level']
  content: string
  hit: boolean
  quote: string
}

export interface JudgeAnswerView {
  id: number
  followupId: string | null
  questionText: string
  layer: number
  answerText: string
  verdict: JudgeVerdict | null
  source: JudgeSource
  points: JudgePointResult[]
  wrongNote: string | null
  errorNote: string | null
  createdAt: string
}

export interface JudgeSessionView {
  id: number
  kpId: number
  kpTitle: string
  kpSummary: string | null
  level: JudgeLevel
  stopLayer: number | null
  status: 'open' | 'closed'
  grade: JudgeGrade | null
  gradeNote: string | null
  degraded: boolean
  createdAt: string
  closedAt: string | null
  followups: KbFollowup[]
  scoringPoints: KbScoringPoint[]
  answers: JudgeAnswerView[]
}

export interface JudgeSessionItemView {
  id: number
  kpId: number
  kpTitle: string
  level: JudgeLevel
  stopLayer: number | null
  status: 'open' | 'closed'
  grade: JudgeGrade | null
  degraded: boolean
  answerCount: number
  createdAt: string
  closedAt: string | null
}

export interface JudgeAnswerResp {
  answer: JudgeAnswerView
  degraded: boolean
  advice: string
}

// —— 个人档案（核心模块定义 4.1，T10） ——

/** 目标职级四档（6.5 Target Level）：与 JudgeLevel 同口径，全产品的深度标尺 */
export type TargetLevel = JudgeLevel

/** 档案状态机：草稿 → 确认 → 定稿；存在未确认技能条不得定稿，未定稿不参与学习路径生成 */
export type ProfileStatus = 'draft' | 'confirmed' | 'finalized'

/** 技能条拍板状态：对话整版采纳即拍板；手动加条走未确认 → 拍板 */
export type ClaimStatus = 'unconfirmed' | 'confirmed'

/** 技能条来源：对话起草采纳 / 手动添加 */
export type ClaimSource = 'ai' | 'manual'

/** 锚定映射状态：unmapped 未映射 / mapped 已锚定 / pending 低置信待确认 / zero 零命中 */
export type AnchorStatus = 'unmapped' | 'mapped' | 'pending' | 'zero'

/** 档案列表项：含技能条计数，供"我的"页档案切换与状态一览 */
export interface ProfileListItem {
  id: number
  name: string
  status: ProfileStatus
  targetLevel: TargetLevel | null
  active: boolean
  claimCount: number
  unconfirmedCount: number
  createdAt: string
  updatedAt: string
}

/** 技能条锚点视图：id + 题面实时反查 */
export interface ClaimAnchor {
  id: number
  title: string
}

/** 技能条（Skill Claim）：一段简历措辞 + 锚定知识点集合 */
export interface ClaimView {
  id: number
  title: string
  wording: string
  anchorIds: number[]
  anchors: ClaimAnchor[]
  anchorStatus: AnchorStatus
  source: ClaimSource
  status: ClaimStatus
  sortOrder: number
}

/** 项目素材（Case）：背景/数字/冲突/结果四段，供叙事练习与模拟考追问消费 */
export interface CaseView {
  id: number
  projectName: string
  background: string
  metrics: string
  conflict: string
  result: string
  createdAt: string
  updatedAt: string
}

/** 档案详情：档案 + 技能条 + 项目素材 */
export interface ProfileDetail {
  id: number
  name: string
  resumeText: string
  years: number | null
  position: string
  jdText: string
  techStack: string
  goodAt: string
  targetLevel: TargetLevel | null
  planWeeks: number | null
  dailyMinutes: number | null
  status: ProfileStatus
  active: boolean
  finalizedAt: string | null
  createdAt: string
  updatedAt: string
  claims: ClaimView[]
  cases: CaseView[]
}

/** 建档对话里的一条技能条：小标题 + 正文（未提供的事实以【】占位） */
export interface IntakeClaim {
  title: string
  content: string
}

/** 建档对话消息：user = 反馈原文；assistant = 教练回复（reply + 该轮完整版本） */
export interface IntakeMessageView {
  id: number
  role: 'user' | 'assistant'
  text: string
  claims: IntakeClaim[] | null
  createdAt: string
}

/** 建档对话历史：round 为教练输出轮数（上限 5） */
export interface IntakeHistoryView {
  round: number
  messages: IntakeMessageView[]
}

/** 一轮教练输出；coverageGaps = 用户技术栈中未被草案覆盖的组件（遗漏提示，可一键补写） */
export interface ChatTurnView {
  round: number
  reply: string
  claims: IntakeClaim[]
  coverageGaps?: string[]
}

/** C 端知识点候选（锚点挑选）：只含已上线知识点的轻量字段 */
export interface KpCandidate {
  id: number
  title: string
  summary: string | null
  difficulty: string
  domainCode: string
  topicName: string
}

// —— 学习路径与练习（核心模块定义 4.2，T11） ——

/** 知识点学习状态五态（learn_status 常量组） */
export type LearnStatus = 'learning' | 'quick' | 'pass' | 'weak' | 'abandoned'

/** 路径准入信号：claim 技能条锚定 / baseline 职级基线 / jd JD 命中 */
export type KpSignal = 'claim' | 'baseline' | 'jd'

/** 路径内的知识点行（轻量字段；详情在练习页取） */
export interface PathKpView {
  id: number
  title: string
  difficulty: string
  signals: KpSignal[]
  state: LearnStatus
  locked: boolean
  freqTotal: number
  followupLayers: number
  docCount: number
}

/** 学习单元（阶段内以主题为界的推进单位） */
export interface PathUnitView {
  topicId: number
  topicName: string
  kpCount: number
  done: number
  total: number
  locked: boolean
  signals: KpSignal[]
  kps: PathKpView[]
}

/** 学习阶段（基础阶段跨域，或领域阶段） */
export interface PathStageView {
  key: string
  kind: 'baseline' | 'domain'
  domainCode: string | null
  domainName: string | null
  title: string
  done: number
  total: number
  units: PathUnitView[]
}

/** 路径总进度摘要（不含放弃题） */
export interface PathSummary {
  done: number
  total: number
  passCount: number
  quickCount: number
  weakCount: number
  nextTopicId: number | null
  nextTopicName: string | null
  nextStageTitle: string | null
}

/** 放弃清单项 */
export interface AbandonedView {
  kpId: number
  title: string
  reason: string | null
  abandonedAt: string | null
}

/** 学习路径全量视图 */
export interface LearnPathView {
  profileId: number
  profileName: string
  targetLevel: string | null
  summary: PathSummary
  stages: PathStageView[]
  abandoned: AbandonedView[]
}

/** 速览/放弃/恢复的轻量返回 */
export interface LearnStateView {
  kpId: number
  status: LearnStatus
  progressedAt: string | null
}

/** 练习会话视图（判定会话 + kp 展示结构 + 挂载资料 + 目标刻度） */
export interface PracticeSessionView {
  id: number
  kpId: number
  topicId: number
  kpTitle: string
  kpSummary: string | null
  difficulty: string
  frameworkMd: string
  level: JudgeLevel
  stopLayer: number | null
  followupLayers: number
  status: 'open' | 'closed'
  grade: JudgeGrade | null
  gradeNote: string | null
  degraded: boolean
  state: LearnStatus
  variants: KbVariant[]
  answerSamples: KbAnswerSample[]
  docs: DocBrief[]
  scoringPoints: KbScoringPoint[]
  followups: KbFollowup[]
  answers: JudgeAnswerView[]
  createdAt: string
  closedAt: string | null
}

/** 练习小结（close 返回） */
export interface PracticeCloseView {
  session: PracticeSessionView
  state: LearnStatus
}

/** 题面预览（选题后、选节奏前；不开判定会话） */
export interface PracticeBriefView {
  kpId: number
  topicId: number
  kpTitle: string
  kpSummary: string | null
  difficulty: string
  frameworkMd: string
  level: JudgeLevel
  stopLayer: number | null
  followupLayers: number
  state: LearnStatus
  variants: KbVariant[]
  answerSamples: KbAnswerSample[]
  docs: DocBrief[]
  scoringPoints: KbScoringPoint[]
  followups: KbFollowup[]
}

/** 资料讲解轻量项（练习页"看不懂"展开层） */
export interface DocBrief {
  id: number
  title: string
  summary: string | null
  difficulty: string
}

// —— 今日复习（核心模块定义 4.3，T12） ——

export type ReviewMode = 'today' | 'draw'

/** 复习卡片信号 tag：到期复测 / 弱点 / 抽查验伪 / 技能条新题 */
export type ReviewTag = 'due' | 'weak' | 'spot' | 'new' | 'draw'

/** 今日复习 / 随机抽取的题目卡片（题面自测 + 自评后展开三档样例；轻载荷） */
export interface ReviewItemView {
  kpId: number
  title: string
  difficulty: string
  state: LearnStatus
  tags: ReviewTag[]
  followupLayers: number
  docCount: number
  variants: KbVariant[]
  answerSamples: KbAnswerSample[]
  dueAt: string | null
}

/** 今日清单池量统计（空态文案与引导用） */
export interface ReviewStats {
  due: number
  weak: number
  quick: number
  newCount: number
}

/** 今日清单视图 */
export interface ReviewTodayView {
  date: string
  profileName: string
  targetLevel: string | null
  items: ReviewItemView[]
  stats: ReviewStats
}

/** 自评结果：排期与证据（progressed 行有 dueAt/interval；未学行仅落流水） */
export interface ReviewResultView {
  kpId: number
  state: LearnStatus
  dueAt: string | null
  intervalDays: number
  failCount: number
}

/** 随机抽取返回（单题 + 池量） */
export interface DrawResp {
  item: ReviewItemView
  poolSize: number
}
