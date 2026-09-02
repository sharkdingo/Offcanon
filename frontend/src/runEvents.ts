import type { RunEvent } from './api'

export type RunEventCategory = 'preparation' | 'agent' | 'result' | 'verification' | 'promotion' | 'memory'
export type RunEventImportance = 'primary' | 'secondary' | 'diagnostic'

type EventDefinition = {
  zh: string
  en: string
  category: RunEventCategory
  importance: RunEventImportance
}

export const RUN_EVENT_DEFINITIONS = {
  EXPERIMENT_STARTED: { zh: '已启动隔离实验', en: 'Isolated experiment started', category: 'preparation', importance: 'primary' },
  RUN_CONFIGURATION_RESOLVED: { zh: '已固定本次运行配置', en: 'Run configuration fixed', category: 'preparation', importance: 'secondary' },
  SESSION_CONTEXT_IMPORTED: { zh: '已承接上一轮任务上下文', en: 'Previous task context carried forward', category: 'preparation', importance: 'secondary' },
  CONTEXT_SNAPSHOT: { zh: '已保留当前上下文', en: 'Current context retained', category: 'agent', importance: 'diagnostic' },
  CONTEXT_COMPACTED: { zh: '已整理运行上下文', en: 'Run context compacted', category: 'agent', importance: 'secondary' },
  MODEL_REQUEST: { zh: '正在等待模型响应', en: 'Waiting for the model response', category: 'agent', importance: 'primary' },
  MODEL_RESPONSE: { zh: '已完成一步规划', en: 'Planning step completed', category: 'agent', importance: 'primary' },
  AGENT_PROGRESS: { zh: 'Agent 更新了行动说明', en: 'Agent shared a progress update', category: 'agent', importance: 'primary' },
  MODEL_RETRY: { zh: '模型暂时不可用，准备重试', en: 'Model unavailable; preparing to retry', category: 'agent', importance: 'primary' },
  TOOL_CALL: { zh: '正在执行工具', en: 'Running a tool', category: 'agent', importance: 'primary' },
  TOOL_RESULT: { zh: '工具执行完成', en: 'Tool finished', category: 'agent', importance: 'primary' },
  AGENT_COMPLETED: { zh: 'Agent 已完成代码工作', en: 'Agent finished the code work', category: 'result', importance: 'primary' },
  RESULT_SNAPSHOT_SEALED: { zh: '已封存不可变结果', en: 'Immutable result sealed', category: 'result', importance: 'primary' },
  TASK_MEMORY_AGENT_PROPOSAL_RECORDED: { zh: '已保留一条 Agent 记忆建议', en: 'Agent memory proposal retained', category: 'memory', importance: 'secondary' },
  TASK_MEMORY_VERIFIED_FACT_RECORDED: { zh: '已保留一条验证事实', en: 'Verified fact retained', category: 'memory', importance: 'secondary' },
  TASK_MEMORY_UNAVAILABLE: { zh: '任务记忆暂时不可用', en: 'Task memory unavailable', category: 'memory', importance: 'secondary' },
  TASK_MEMORY_RECORD_FAILED: { zh: '任务记忆未能保留', en: 'Task memory could not be retained', category: 'memory', importance: 'secondary' },
  VERIFICATION_CONFIGURATION_RESOLVED: { zh: '已固定本次验收配置', en: 'Acceptance configuration fixed', category: 'verification', importance: 'secondary' },
  VERIFICATION_STARTED: { zh: '正在运行项目验收', en: 'Running project acceptance checks', category: 'verification', importance: 'primary' },
  VERIFICATION_WAITING: { zh: '结果已保留，等待验收命令', en: 'Result retained; waiting for acceptance commands', category: 'verification', importance: 'primary' },
  VERIFICATION_INVALIDATED: { zh: '验收命令已修改，结果等待重新验收', en: 'Acceptance commands changed; result awaits re-verification', category: 'verification', importance: 'primary' },
  VERIFICATION_INTERRUPTED: { zh: '验收已中断，结果仍可重验', en: 'Verification interrupted; result can be checked again', category: 'verification', importance: 'primary' },
  VERIFICATION_FINISHED: { zh: '项目验收已完成', en: 'Project acceptance checks finished', category: 'verification', importance: 'primary' },
  PROMOTION_VERIFICATION_STARTED: { zh: '正在复核应用候选', en: 'Checking the application candidate', category: 'promotion', importance: 'primary' },
  PROMOTION_PREPARING: { zh: '正在写入真实项目', en: 'Writing to the real project', category: 'promotion', importance: 'primary' },
  PROMOTION_BLOCKED: { zh: '应用已被阻止', en: 'Application blocked', category: 'promotion', importance: 'primary' },
  PROMOTED: { zh: '结果已应用到真实项目', en: 'Result applied to the real project', category: 'promotion', importance: 'primary' },
  PROMOTION_RECOVERY_REQUIRED: { zh: '应用状态需要恢复', en: 'Application state needs recovery', category: 'promotion', importance: 'primary' },
  PROMOTION_RECOVERY_DEFERRED: { zh: '应用恢复已延期', en: 'Application recovery deferred', category: 'promotion', importance: 'primary' },
  PROMOTION_RECOVERED: { zh: '应用状态已恢复', en: 'Application state recovered', category: 'promotion', importance: 'primary' },
  PROMOTION_MANUALLY_RECONCILED: { zh: '应用状态已人工确认', en: 'Application state manually reconciled', category: 'promotion', importance: 'primary' },
  PROMOTION_FINISHED: { zh: '应用流程已结束', en: 'Application attempt finished', category: 'promotion', importance: 'primary' },
  EXPERIMENT_FAILED: { zh: '实验运行失败', en: 'Experiment failed', category: 'result', importance: 'primary' },
  EXPERIMENT_CANCELLED: { zh: '实验已取消', en: 'Experiment cancelled', category: 'result', importance: 'primary' },
  EXPERIMENT_RECOVERED: { zh: '已恢复中断的实验', en: 'Interrupted experiment recovered', category: 'result', importance: 'primary' },
} as const satisfies Record<string, EventDefinition>

export type KnownRunEventType = keyof typeof RUN_EVENT_DEFINITIONS

export const RUN_EVENTS_REQUIRING_REFRESH: ReadonlySet<string> = new Set<KnownRunEventType>([
  'EXPERIMENT_STARTED',
  'AGENT_COMPLETED',
  'RESULT_SNAPSHOT_SEALED',
  'VERIFICATION_STARTED',
  'VERIFICATION_WAITING',
  'VERIFICATION_INVALIDATED',
  'VERIFICATION_INTERRUPTED',
  'VERIFICATION_CONFIGURATION_RESOLVED',
  'VERIFICATION_FINISHED',
  'PROMOTION_PREPARING',
  'PROMOTION_VERIFICATION_STARTED',
  'PROMOTION_BLOCKED',
  'EXPERIMENT_FAILED',
  'PROMOTION_RECOVERY_REQUIRED',
  'PROMOTION_RECOVERY_DEFERRED',
  'PROMOTION_RECOVERED',
  'PROMOTION_MANUALLY_RECONCILED',
  'PROMOTION_FINISHED',
  'PROMOTED',
  'EXPERIMENT_RECOVERED',
  'EXPERIMENT_CANCELLED',
])

export function runEventDefinition(type: string): EventDefinition {
  return RUN_EVENT_DEFINITIONS[type as KnownRunEventType] ?? {
    zh: type.replaceAll('_', ' '),
    en: type.replaceAll('_', ' ').toLowerCase(),
    category: 'agent',
    importance: 'diagnostic',
  }
}

export function runEventLabel(event: RunEvent, localize: (zh: string, en: string) => string) {
  if (event.type === 'EXPERIMENT_RECOVERED') {
    const status = typeof event.payload.status === 'string' ? event.payload.status : ''
    if (status === 'FAILED') return localize('中断实验已收口为失败', 'Interrupted run closed as failed')
    if (status === 'AGENT_COMPLETED') return localize('已恢复为待验收状态', 'Recovered; result awaits verification')
  }
  if (event.type === 'PROMOTION_FINISHED') {
    const promoted = event.payload.promoted === true
    return localize(promoted ? '应用流程已完成' : '应用流程已结束（未应用）', promoted ? 'Application completed' : 'Application ended without applying')
  }
  const definition = runEventDefinition(event.type)
  return localize(definition.zh, definition.en)
}
