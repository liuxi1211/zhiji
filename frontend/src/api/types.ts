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
