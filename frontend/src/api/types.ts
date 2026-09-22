/**
 * 后端契约的枚举与类型。
 *
 * 架构文档 2.1 选 TypeScript 的理由就在这里：接口里的枚举多
 * （subType / verdictLevel / nextAction / layerKey），
 * 用类型把后端契约固化到前端，能在编译期挡住字段拼写与取值错误。
 * 取值必须与后端 CHECK 约束、考点库设计 2.6 / 2.8 保持一致。
 */

/** 题型：与 kp_main.sub_type 的 CHECK 约束一致 */
export type SubType = 'default' | 'design_chain' | 'narrative'

/** 考点状态机：draft → reviewed → active → retired（考点库设计 2.11） */
export type KpStatus = 'draft' | 'reviewed' | 'active' | 'retired'

/** 四档：由要点命中深度分布算出，LLM 不碰分数（企划书） */
export type VerdictLevel = 'excellent' | 'good' | 'pass' | 'fail'

/** 答况四分流决定的下一动作（接口 I-09） */
export type NextAction = 'next_layer' | 'same_layer_variant' | 'stop_and_correct' | 'regress_prereq'

/** 路径三层：底线层 / 主攻层 / 放弃层（考点库设计 4.9） */
export type PathLayer = 'baseline' | 'main' | 'drop'

/** 评分要点分级：基础 / 中级 / 资深（考点库设计 2.8） */
export type RubricLevel = '基础' | '中级' | '资深'

/** 作答来源：打字或语音转写 */
export type AnswerSrc = 'text' | 'voice'

/** 后端统一错误体（GlobalExceptionHandler 输出） */
export interface ApiError {
  status: number
  code: string
  message: string
  fields?: Record<string, string>
}

/** 判定命中的要点快照：必须带 layerKey + level，rubric 改动才不影响历史可比性（2.12） */
export interface VerdictHit {
  layerKey: string
  level: RubricLevel
  pointText: string
  /** 引用强制：命中要点必须附用户原话，引不出算未命中 */
  quote: string
}

export interface Verdict {
  verdictLevel: VerdictLevel
  hits: VerdictHit[]
  appealAllowed: boolean
}
