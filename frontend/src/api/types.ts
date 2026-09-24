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

/** 知识库域：频率信号来源（库级三来源，复盘信号属个人侧不进库） */
export type FreqSource = 'manual' | 'interview' | 'coverage'
export type FreqLevel = 'high' | 'mid' | 'low'
/** 技能域状态（域字典管理随 T3） */
export type DomainStatus = 'active' | 'disabled'

export interface CaptchaResp {
  captchaId: string
  svg: string
}

export interface CustomerAuthResp {
  token: string
  username: string
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

/** LLM 调用入口（用途）：业务按用途调用，不认具体模型名（核心模块定义 4.2；T6 新增 B 端生产类三项） */
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
  | 'doc_form_detect'
  | 'question_gen'
  | 'interview_extract'

/** 单条模型路由目标：provider 名对应后端 zhiji.llm.providers 的 key */
export interface LlmRouteTargetView {
  provider: string
  model: string
  temperature: number | null
  maxTokens: number | null
  embedding: boolean
}

export interface LlmLimitsView {
  judgeDaily: number
  variantRewriteDaily: number
  perProfileDailyTotal: number
  siteDailyTotal: number
  /** D32：全站熔断护栏开关（默认关闭；启用后仅统计 C 端消费类） */
  siteCircuitEnabled: boolean
}

export interface LlmSettingsView {
  routes: Record<string, LlmRouteTargetView>
  limits: LlmLimitsView
  providers: string[]
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

/** 判定驱动的推进状态：状态机只允许 draft → reviewed → active 正向推进 */
export type KpForwardStatus = 'reviewed' | 'active'

/** 追问层：layerKey 是稳定身份，永不复用永不修改（数组下标只作展示排序） */
export interface KpFollowup {
  layerKey: string
  dir: string
  text: string
  expect: string | null
  stopCondition: string | null
  status: 'active' | 'disabled'
}

/** 评分要点：预制评分要点是尺子，LLM 只做对齐判断不做知识判断 */
export interface KpRubricPoint {
  pointId: string
  level: RubricLevel
  text: string
}

export interface KpRubricGroup {
  layerKey: string
  points: KpRubricPoint[]
}

/** 三档样例：及格/良好/优秀 */
export type KpSampleSet = { pass: string; good: string; excellent: string }

/** 职级达标要求：每职级是否必答 + 停止追问层 */
export type KpLevelReq = Record<LevelTarget, { required: boolean; stopLayer: string | null }>

export interface KpClusterListItem {
  id: number
  domainId: number
  domainName: string
  name: string
  description: string | null
  status: KpStatus
  kpCount: number
  lastReviewedAt: string | null
  reviewDueAt: string | null
  createdAt: string
  updatedAt: string
}

export interface KpClusterDetail extends Omit<KpClusterListItem, 'kpCount'> {
  zeroShotMd: string | null
  referenceSolutionMd: string | null
  mergedInto: number | null
  kpList: { id: number; title: string; status: KpStatus; difficulty: number }[]
}

export interface KpListItem {
  id: number
  title: string
  domainId: number
  domainName: string
  clusterId: number
  clusterName: string
  subType: SubType
  difficulty: number
  status: KpStatus
  baselineLevels: string
  lastReviewedAt: string | null
  reviewDueAt: string | null
}

export interface KpFreqSignalView {
  id: number
  source: FreqSource
  signalLevel: FreqLevel | null
  signalCount: number
  note: string | null
  createdAt: string
}

export interface KpDetail {
  id: number
  clusterId: number
  clusterName: string
  domainId: number
  domainName: string
  subType: SubType
  title: string
  variants: string[]
  narrativeFramework: string | null
  followups: KpFollowup[]
  rubric: KpRubricGroup[]
  samples: KpSampleSet
  levelReq: KpLevelReq
  difficulty: number
  status: KpStatus
  mergedInto: number | null
  lastReviewedAt: string | null
  reviewDueAt: string | null
  prereqIds: number[]
  prereqList: { id: number; title: string }[]
  freqSignals: KpFreqSignalView[]
}

/** 知识点保存载荷：followups/rubric 等整体提交，layerKey 由 addFollowupLayer 先行分配 */
export interface KpSavePayload {
  clusterId: number
  subType: SubType
  title: string
  difficulty: number
  variants?: string[]
  narrativeFramework?: string
  followups?: KpFollowup[]
  rubric?: KpRubricGroup[]
  samples?: KpSampleSet
  levelReq?: KpLevelReq
  prereqIds?: number[]
}

/** 下拉元数据：域字典 + 簇清单，供筛选器与表单选项共用 */
export interface KpMeta {
  domains: { id: number; name: string; status: DomainStatus; kpCount: number }[]
  clusters: { id: number; name: string; domainId: number; status: KpStatus }[]
}

/**
 * 知识卡分享公开视图（知识库PRD 7.5）：GET /api/share/kp/{kpId} 无鉴权返回。
 * 红线：只含 active 库内容，不含 levelReq / rubric（评分要点）与任何个人数据。
 */
export interface ShareKpDetail {
  id: number
  title: string
  subType: SubType
  difficulty: number
  variants: string[]
  narrativeFramework: string | null
  followups: KpFollowup[]
  samples: KpSampleSet
  domainName: string | null
  clusterName: string | null
  zeroShotMd: string | null
  referenceSolutionMd: string | null
}

/* ================= 素材库（pipeline，T6）================= */

/** 素材内容形态（决策清单 D22 三分类路由） */
export type SourceForm = 'question_bank' | 'reading' | 'interview'

/** 素材文档状态机（附录 A 8.2 source_doc_status） */
export type SourceDocStatus = 'pending' | 'parsed' | 'parse_failed' | 'split'

/** 候选条目状态机（附录 A 8.2 candidate_status） */
export type CandidateStatus = 'pending' | 'matched' | 'converting' | 'converted' | 'merged' | 'discarded'

export interface ParsedHeading {
  level: number
  title: string
  line: number
}

/** 素材文档视图：rawText 只在详情接口返回（列表为 null） */
export interface SourceDocView {
  id: number
  batchId: string
  fileName: string
  sourceRef: string | null
  form: SourceForm | null
  status: SourceDocStatus
  parseError: string | null
  charCount: number
  headingTree: string | null
  rawText: string | null
  createdAt: string
  updatedAt: string
}

/** 批次聚合（批次面板：文件数 / 各状态计数 / 失败清单） */
export interface BatchSummary {
  batchId: string
  fileCount: number
  pendingCount: number
  parsedCount: number
  failedCount: number
  splitCount: number
  createdAt: string
  failures: { id: number; fileName: string; error: string }[]
}

/** 候选条目视图（拆题产物，溯源回素材文档） */
export interface CandidateItem {
  id: number
  docId: number
  batchId: string
  title: string
  answerEvidence: string | null
  sectionPath: string | null
  form: SourceForm
  status: CandidateStatus
  confidence: 'high' | 'low' | null
  note: string | null
}

export interface DocDetail {
  doc: SourceDocView
  candidates: CandidateItem[]
}

export interface SplitOutcome {
  inserted: number
}

/* ================= 知识地图（pipeline，T7）================= */

/** 归一题状态机（附录 A 8.2 canonical_status）：AI 聚合待确认 / 人工确认入图 / 已建设成知识点 */
export type CanonicalStatus = 'pending' | 'confirmed' | 'built'

/** 聚合依据：structural 结构强命中 / exact 极高相似 / llm LLM 精判（人工抽检的过滤维度） */
export type CanonicalJudgeBasis = 'structural' | 'exact' | 'llm'

export interface CanonicalItem {
  id: number
  title: string
  clusterId: number | null
  coverageCount: number
  status: CanonicalStatus
  judgeBasis: CanonicalJudgeBasis | null
  builtKpId: number | null
}

/** 源候选溯源视图（答案证据为预览截断，原文走 docId 回跳素材库） */
export interface CanonicalSource {
  candidateId: number
  docId: number
  docFileName: string | null
  title: string
  answerEvidence: string | null
  sectionPath: string | null
  form: SourceForm
  sourceRef: string | null
}

export interface CanonicalDetail {
  canonical: CanonicalItem
  sources: CanonicalSource[]
}

export interface CoverageCluster {
  clusterId: number
  name: string
  domainId: number | null
  domainName: string | null
  canonicalCount: number
  coverageSum: number
  builtCount: number
}

export interface CoverageView {
  domainCount: number
  clusterCount: number
  canonicalCount: number
  builtCount: number
  pendingCount: number
  unclusteredCount: number
  clusters: CoverageCluster[]
}

export interface TreeDomain {
  domainId: number
  domainName: string
  clusterCount: number
  canonicalCount: number
  coverageSum: number
}

export interface TreeView {
  domains: TreeDomain[]
  unclusteredCount: number
}

/** 运行归一结果摘要 */
export interface RunSummary {
  looseCount: number
  newCanonicals: number
  absorbedItems: number
  mappedKp: number
  newClusters: number
  embeddingOk: boolean
  llmJudgeTruncated: number
}

/** AI 簇命名建议（只建议不落库，采纳走既有簇更新接口） */
export interface NameSuggestion {
  name: string
  domainId: number | null
  domainName: string | null
}
