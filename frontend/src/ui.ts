export type StatusTone = 'neutral' | 'experiment' | 'active' | 'success' | 'warning' | 'danger'

const ZH_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  SNAPSHOTTING: '创建隔离区',
  READY_TO_RUN: '待运行',
  RUNNING: '运行中',
  AGENT_COMPLETED: '代理完成',
  VERIFYING: '验证中',
  VERIFIED: '已验证',
  PREPARING_PROMOTION: '准备应用',
  PROMOTING: '应用中',
  PROMOTED: '已应用',
  FAILED: '失败',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
  STALE: '已过期',
  STALE_DURING_PROMOTION: '主线已变化',
  RECOVERY_REQUIRED: '需要恢复',
}

const EN_STATUS_LABELS: Record<string, string> = {
  CREATED: 'Created',
  SNAPSHOTTING: 'Preparing workspace',
  READY_TO_RUN: 'Ready',
  RUNNING: 'Running',
  AGENT_COMPLETED: 'Agent finished',
  VERIFYING: 'Verifying',
  VERIFIED: 'Verified',
  PREPARING_PROMOTION: 'Preparing update',
  PROMOTING: 'Updating project',
  PROMOTED: 'Applied',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  STALE: 'Out of date',
  STALE_DURING_PROMOTION: 'Canonical changed',
  RECOVERY_REQUIRED: 'Recovery required',
}

export function statusLabel(status: string, locale?: string) {
  const resolvedLocale = locale ?? (typeof document === 'undefined' ? 'en-US' : document.documentElement.lang)
  return resolvedLocale === 'zh-CN'
    ? ZH_STATUS_LABELS[status] ?? status.replaceAll('_', ' ')
    : EN_STATUS_LABELS[status] ?? status.replaceAll('_', ' ').toLowerCase()
}

export function statusTone(status: string): StatusTone {
  if (status === 'VERIFIED' || status === 'PROMOTED') return 'success'
  if (status === 'RECOVERY_REQUIRED' || status === 'STALE') return 'warning'
  if (status === 'FAILED' || status === 'REJECTED' || status === 'CANCELLED') return 'danger'
  if (['RUNNING', 'VERIFYING', 'PREPARING_PROMOTION', 'PROMOTING'].includes(status)) return 'active'
  if (['CREATED', 'SNAPSHOTTING', 'READY_TO_RUN', 'AGENT_COMPLETED'].includes(status)) return 'experiment'
  return 'neutral'
}

export function shortId(value: string | null | undefined, length = 8) {
  return value ? value.slice(0, length).toUpperCase() : 'PENDING'
}

export function shortFingerprint(value: string | null | undefined) {
  return value ? value.slice(0, 12) : 'not available'
}

export function formatDate(value: string) {
  // Dates are part of the app's language surface, not the browser profile.
  // Auth settings keep document.lang in sync with the selected locale.
  const locale = typeof document !== 'undefined' && document.documentElement.lang
    ? document.documentElement.lang
    : undefined
  return new Intl.DateTimeFormat(locale, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function formatDuration(milliseconds: number) {
  if (milliseconds < 1000) return `${milliseconds} ms`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} s`
}

/** Convert API/domain failures into consistent product language. */
export function formatError(cause: unknown, zhFallback: string, enFallback: string) {
  const value = cause && typeof cause === 'object' ? cause as { code?: unknown } : null
  const code = typeof value?.code === 'string' ? value.code : ''
  const messages: Record<string, [string, string]> = {
    UNAUTHORIZED: ['登录状态已失效，请重新登录。', 'Your sign-in has expired. Please sign in again.'],
    FORBIDDEN: ['当前账户没有执行此操作的权限。', 'This account is not allowed to perform that action.'],
    NOT_FOUND: ['请求的内容已不存在或不可用。', 'The requested content is no longer available.'],
    USERNAME_TAKEN: ['这个用户名已被使用。', 'That username is already in use.'],
    PROJECT_ALREADY_REGISTERED: ['这个 Git 仓库已在其他账户中打开。', 'This Git repository is already open in another account.'],
    VERIFICATION_POLICY_MISSING: ['项目至少需要一条验收命令。', 'The project needs at least one acceptance command.'],
    MODEL_NOT_CONFIGURED: ['模型服务尚未配置，请打开设置完成配置。', 'The model service is not configured. Open Settings to finish setup.'],
    MODEL_ENDPOINT_INVALID: ['模型 Endpoint 必须是有效的 HTTP(S) 地址。', 'The model endpoint must be a valid HTTP(S) URL.'],
    MODEL_API_KEY_INVALID: ['模型 API key 无效或过长。', 'The model API key is invalid or too long.'],
    MODEL_REQUEST_FAILED: ['模型服务拒绝了请求，请检查连接设置。', 'The model service rejected the request. Check the connection settings.'],
    MODEL_TRANSIENT_FAILURE: ['模型服务暂时不可用，请稍后重试。', 'The model service is temporarily unavailable. Try again later.'],
    MODEL_CONNECTION_FAILED: ['无法连接模型服务，请检查 Endpoint、模型名和 API key。', 'Could not connect to the model service. Check the endpoint, model, and API key.'],
    PROJECT_NOT_FOUND: ['项目已不存在，请刷新项目列表。', 'The project is no longer available. Refresh the project list.'],
    PROJECT_PATH_IMMUTABLE: ['项目路径不能修改；请重新打开正确的仓库。', 'A project path cannot be changed; reopen the correct repository.'],
    VERIFICATION_POLICY_LOCKED: ['项目已有实验后不能修改验收命令。', 'Acceptance commands cannot change after experiments exist.'],
    PROJECT_VERSION_CONFLICT: ['项目刚刚被其他操作更新，请刷新后重试。', 'The project changed in another operation. Refresh and try again.'],
    MODEL_REQUEST_INVALID: ['模型请求格式无效，请检查连接设置。', 'The model request was invalid. Check the connection settings.'],
    MODEL_RESPONSE_INVALID: ['模型返回了无法识别的结果，请重试或更换模型。', 'The model returned an unreadable result. Retry or choose another model.'],
    MODEL_RESPONSE_TOO_LARGE: ['模型返回内容过大，请缩小任务范围后重试。', 'The model response was too large. Narrow the task and retry.'],
    SESSION_ALREADY_RUNNING: ['该会话已有任务在运行，请等待完成。', 'This session already has a running task. Wait for it to finish.'],
    EXPERIMENT_ALREADY_RUNNING: ['该实验已经在运行，请等待完成。', 'This experiment is already running. Wait for it to finish.'],
    DANGEROUS_COMMAND_BLOCKED: ['为保护项目，已阻止这条命令。', 'This command was blocked to protect the project.'],
    WORKSPACE_NOT_READY: ['隔离工作区尚未准备好，请稍后重试。', 'The isolated workspace is not ready yet. Try again shortly.'],
    VERIFICATION_MUTATED_SOURCE: ['验证期间项目源文件发生变化，结果已作废。', 'Source files changed during verification; the result was invalidated.'],
    MANUAL_RECOVERY_REQUIRED: ['项目需要手动恢复，请先完成恢复操作。', 'The project needs manual recovery before continuing.'],
    PROMOTION_RECOVERY_PENDING: ['项目有未完成的应用操作，请先恢复状态。', 'An unfinished project update must be reconciled first.'],
    AGENT_MAX_STEPS_OUT_OF_POLICY: ['最大步数超过应用安全上限。', 'Max steps exceed the application safety limit.'],
    AGENT_TIMEOUT_OUT_OF_POLICY: ['运行超时超过应用安全上限。', 'Run timeout exceeds the application safety limit.'],
    AGENT_CONTEXT_OUT_OF_POLICY: ['上下文上限超过应用安全上限。', 'Context limit exceeds the application safety limit.'],
    AGENT_TIMEOUT: ['运行超时，可以缩小任务范围后重试。', 'The run timed out. Narrow the task and try again.'],
    MAX_STEPS_EXCEEDED: ['运行达到步数上限，可以缩小任务范围后重试。', 'The run reached its step limit. Narrow the task and try again.'],
    TOOL_CALL_LIMIT_EXCEEDED: ['工具调用达到上限，可以缩小任务范围后重试。', 'The tool-call limit was reached. Narrow the task and try again.'],
    SETTINGS_VERSION_CONFLICT: ['设置刚刚被更新，请刷新后再保存。', 'Settings were updated elsewhere. Refresh and save again.'],
    VALIDATION_FAILED: ['提交的信息不完整或格式不正确。', 'Some submitted values are missing or invalid.'],
    INTERNAL_ERROR: ['服务暂时遇到问题，请稍后重试。', 'The service encountered a problem. Try again later.'],
    HTTP_ERROR: ['请求未能完成，请稍后重试。', 'The request could not be completed. Try again later.'],
  }
  const message = messages[code]
  const locale = typeof document !== 'undefined' ? document.documentElement.lang : 'zh-CN'
  return message ? (locale === 'zh-CN' ? message[0] : message[1]) : (locale === 'zh-CN' ? zhFallback : enFallback)
}

export function formatCode(code: string, zhFallback: string, enFallback: string) {
  const messages: Record<string, [string, string]> = {
    MODEL_CONNECTION_OK: ['连接成功。', 'Connection succeeded.'],
    MODEL_NOT_CONFIGURED: ['尚未配置模型服务。', 'Model service is not configured.'],
    MODEL_ENDPOINT_INVALID: ['Endpoint 地址无效。', 'The endpoint URL is invalid.'],
    MODEL_API_KEY_INVALID: ['API key 无效。', 'The API key is invalid.'],
    MODEL_REQUEST_FAILED: ['模型服务拒绝了请求。', 'The model service rejected the request.'],
    MODEL_TRANSIENT_FAILURE: ['模型服务暂时不可用。', 'The model service is temporarily unavailable.'],
    MODEL_CONNECTION_FAILED: ['连接模型服务失败。', 'The model connection failed.'],
  }
  const message = messages[code]
  const locale = typeof document !== 'undefined' ? document.documentElement.lang : 'zh-CN'
  return message ? (locale === 'zh-CN' ? message[0] : message[1]) : (locale === 'zh-CN' ? zhFallback : enFallback)
}
