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

/** 目标职级五档：初级/中级/高级/资深/专家 */
export type LevelTarget = 'junior' | 'mid' | 'senior' | 'staff' | 'expert'

/** 评分要点分级：基础/中级/资深（单层回答内要点深度，与职级五档是两套刻度） */
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

/** 单条模型路由目标：provider 名对应后端 zhiji.llm.providers 的 key；thinking 三态 null=跟随默认 */
export interface LlmRouteTargetView {
  provider: string
  model: string
  temperature: number | null
  maxTokens: number | null
  embedding: boolean
  thinking: boolean | null
}

export interface LlmLimitsView {
  judgeDaily: number
  variantRewriteDaily: number
  perProfileDailyTotal: number
  siteDailyTotal: number
}

export interface LlmSettingsView {
  routes: Record<string, LlmRouteTargetView>
  limits: LlmLimitsView
  providers: string[]
  /** provider 支持的模型清单（yml 事实源）：key=provider 名，value=模型列表（设置页下拉数据源） */
  providerModels: Record<string, string[]>
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

export interface LlmUsageRow {
  purpose: string
  purposeName: string
  /** 调用侧别：C=用户触发（受档案限额）/ B=员工人工触发（仅计量不限流） */
  side: 'C' | 'B'
  /** 该入口全站当日计数（所有档案合计） */
  callCount: number
  /** 该入口单档案当日最高计数——单档案限额按此口径比较 */
  peakProfileCount: number
  limitKey: 'JUDGE' | 'VARIANT' | 'NONE'
  /** 单档案限额；无独立限额的入口为 null */
  limit: number | null
  /** 软上限（判定类）：超限提示但不阻断 */
  soft: boolean
  exceeded: boolean
}

export interface LlmDailyPoint {
  date: string
  callCount: number
}

export interface LlmUsageView {
  date: string
  purposes: LlmUsageRow[]
  todayTotal: number
  siteDailyTotal: number
  circuitOpen: boolean
  /** 单档案当日合计的最高值（对应全局兜底熔断阈值） */
  peakProfileTotal: number
  perProfileDailyTotal: number
  perProfileExceeded: boolean
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

/** 领域清单（5.3 种子草案；后端常量组 domain 下发） */
export type DomainCode =
  | 'java'
  | 'database'
  | 'middleware'
  | 'distributed'
  | 'cs_basic'
  | 'go'
  | 'engineering'

export interface SourceItemView {
  id: number
  parentId: number | null
  title: string
  contentMd: string
  /** 概述齐备是提交入库的门槛 */
  summary: string | null
  difficulty: SourceDifficulty | null
  domainCode: string | null
  domainName: string | null
  topicName: string | null
  sourceType: SourceType
  sourceUrl: string | null
  status: SourceStatus
  submittedAt: string | null
  /** 拆分产出的子素材数（仅母素材有值） */
  childCount: number
  createdAt: string
  updatedAt: string
}

export interface SourceCreateReq {
  title: string
  contentMd?: string
  sourceUrl?: string
}

export interface SourceUpdateReq {
  title?: string
  contentMd?: string
  summary?: string
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
}

export interface SourceSplitSliceInput {
  title: string
  contentMd?: string
}

export interface SourceSplitSliceResp {
  id: number
  title: string
}

export interface SourceSplitResp {
  slices: SourceSplitSliceResp[]
}

/** AI 改写产物：正文回编辑器（kp/doc 定类由向导入口预置 + 提交时人工选择） */
export interface SourceRewriteResp {
  content: string
}

/** AI 一键拆分预填：anchor 为原文标题锚点，前端据此切正文 */
export interface SourceSplitSuggestion {
  title: string
  anchor: string
}

export interface SourceAiSplitResp {
  needSplit: boolean
  slices: SourceSplitSuggestion[]
}

/** AI 分类建议：仅领域内推荐主题位置，采纳或无视由人 */
export interface SourceClassifyResp {
  topicName: string
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
  level: 'junior' | 'mid' | 'senior' | 'expert' | 'master'
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

/** 判定目标职级五档（与 KbLevelRule.level 同口径） */
export type JudgeLevel = 'junior' | 'mid' | 'senior' | 'expert' | 'master'

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

/** 目标职级五档（6.5 Target Level）：与 JudgeLevel 同口径，全产品的深度标尺 */
export type TargetLevel = JudgeLevel

/** 档案状态机：草稿 → 确认 → 定稿；存在未确认技能条不得定稿，未定稿不参与学习路径生成 */
export type ProfileStatus = 'draft' | 'confirmed' | 'finalized'

/** 技能条拍板状态：措辞或锚点变更后回到待确认，重走拍板 */
export type ClaimStatus = 'unconfirmed' | 'confirmed'

/** 技能条来源：AI 起草采纳 / 手动添加 */
export type ClaimSource = 'ai' | 'manual'

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
  wording: string
  anchorIds: number[]
  anchors: ClaimAnchor[]
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

/** AI 起草的技能条草稿（产物不落库，逐条采纳） */
export interface ClaimDraft {
  wording: string
  anchors: ClaimAnchor[]
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
