import type { Experiment, ExperimentStatus } from './api'

export type StatusTone = 'neutral' | 'experiment' | 'active' | 'success' | 'warning' | 'danger'

const ZH_STATUS_LABELS = {
  CREATED: '已创建',
  SNAPSHOTTING: '创建隔离区',
  READY_TO_RUN: '待运行',
  RUNNING: '运行中',
  AGENT_COMPLETED: '代理已完成',
  VERIFYING: '验收中',
  VERIFIED: '已通过验收',
  PREPARING_PROMOTION: '准备应用',
  PROMOTING: '应用中',
  PROMOTED: '已应用',
  FAILED: '失败',
  REJECTED: '验收未通过',
  CANCELLED: '已取消',
  STALE: '已过期',
  RECOVERY_REQUIRED: '需要恢复',
} satisfies Record<ExperimentStatus, string>

const EN_STATUS_LABELS = {
  CREATED: 'Created',
  SNAPSHOTTING: 'Preparing workspace',
  READY_TO_RUN: 'Ready',
  RUNNING: 'Running',
  AGENT_COMPLETED: 'Agent finished',
  VERIFYING: 'Checking',
  VERIFIED: 'Checks passed',
  PREPARING_PROMOTION: 'Preparing update',
  PROMOTING: 'Updating project',
  PROMOTED: 'Applied',
  FAILED: 'Failed',
  REJECTED: 'Checks failed',
  CANCELLED: 'Cancelled',
  STALE: 'Out of date',
  RECOVERY_REQUIRED: 'Recovery required',
} satisfies Record<ExperimentStatus, string>

const STATUS_TONES = {
  CREATED: 'experiment',
  SNAPSHOTTING: 'experiment',
  READY_TO_RUN: 'experiment',
  RUNNING: 'active',
  AGENT_COMPLETED: 'experiment',
  VERIFYING: 'active',
  VERIFIED: 'success',
  REJECTED: 'danger',
  STALE: 'warning',
  PREPARING_PROMOTION: 'active',
  PROMOTING: 'active',
  PROMOTED: 'success',
  RECOVERY_REQUIRED: 'warning',
  FAILED: 'danger',
  CANCELLED: 'danger',
} satisfies Record<ExperimentStatus, StatusTone>

export function statusLabel(status: string, locale?: string) {
  const resolvedLocale = locale ?? (typeof document === 'undefined' ? 'en-US' : document.documentElement.lang)
  return resolvedLocale === 'zh-CN'
    ? ZH_STATUS_LABELS[status as ExperimentStatus] ?? (status === 'STALE_DURING_PROMOTION' ? '主线已变化' : status.replaceAll('_', ' '))
    : EN_STATUS_LABELS[status as ExperimentStatus] ?? (status === 'STALE_DURING_PROMOTION' ? 'Canonical changed' : status.replaceAll('_', ' ').toLowerCase())
}

export function statusTone(status: string): StatusTone {
  return STATUS_TONES[status as ExperimentStatus] ?? (status === 'STALE_DURING_PROMOTION' ? 'warning' : 'neutral')
}

/**
 * Resolve the user-facing tone for a lifecycle row.  AGENT_COMPLETED is a
 * durable boundary with two meanings; a sealed result that was interrupted or
 * invalidated should read as a warning even though its persisted enum stays
 * AGENT_COMPLETED for compatibility.
 */
export function experimentDisplayTone(experiment: Pick<Experiment, 'status' | 'resultSnapshotId' | 'failureReason'>): StatusTone {
  if (sealedResultWaiting(experiment)
    && (verificationPolicyChanged(experiment) || Boolean(experiment.failureReason?.trim()))) {
    return 'warning'
  }
  return statusTone(experiment.status)
}

/** Persistent lifecycle states that prevent another experiment owning the same session. */
export function experimentBlocksSession(experiment: Experiment) {
  switch (experiment.status) {
    case 'CREATED':
    case 'SNAPSHOTTING':
    case 'READY_TO_RUN':
    case 'RUNNING':
    case 'VERIFYING':
    case 'PREPARING_PROMOTION':
    case 'PROMOTING':
    case 'RECOVERY_REQUIRED':
      return true
    case 'AGENT_COMPLETED':
      return !experiment.resultSnapshotId
    default:
      return false
  }
}

export function experimentStatusLabel(experiment: Experiment, hasAcceptanceCommands: boolean, locale?: string) {
  if (sealedResultWaiting(experiment)) {
    const resolvedLocale = locale ?? (typeof document === 'undefined' ? 'en-US' : document.documentElement.lang)
    if (verificationPolicyChanged(experiment)) {
      return resolvedLocale === 'zh-CN' ? '验收策略已变化，待重验' : 'Policy changed; reverify'
    }
    if (experiment.failureReason?.trim()) {
      return resolvedLocale === 'zh-CN' ? '验收已中断' : 'Verification interrupted'
    }
    if (resolvedLocale === 'zh-CN') return hasAcceptanceCommands ? '待验收' : '可继续'
    return hasAcceptanceCommands ? 'Awaiting checks' : 'Ready to continue'
  }
  return statusLabel(experiment.status, locale)
}

/** A sealed result passed under an older project acceptance policy. */
export function verificationPolicyChanged(experiment: Pick<Experiment, 'status' | 'resultSnapshotId' | 'failureReason'>) {
  return (experiment.status === 'AGENT_COMPLETED' || experiment.status === 'STALE')
    && Boolean(experiment.resultSnapshotId)
    && Boolean(experiment.failureReason?.startsWith('VERIFICATION_POLICY_CHANGED:'))
}

/**
 * A sealed result that is waiting for trusted verification. The STALE branch
 * is retained for records written by older versions that represented a policy
 * change as stale; new writes use AGENT_COMPLETED for the same boundary.
 */
export function sealedResultWaiting(experiment: Pick<Experiment, 'status' | 'resultSnapshotId' | 'failureReason'>) {
  return Boolean(experiment.resultSnapshotId)
    && (experiment.status === 'AGENT_COMPLETED'
      || (experiment.status === 'STALE' && verificationPolicyChanged(experiment)))
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
    VERIFICATION_POLICY_TOO_LARGE: ['验收命令最多 20 条。', 'A project can have at most 20 acceptance commands.'],
    VERIFICATION_COMMAND_TOO_LARGE: ['单条验收命令不能超过 1000 个字符。', 'Each acceptance command cannot exceed 1,000 characters.'],
    DIRECTORY_PATH_ABSOLUTE_REQUIRED: ['项目目录必须是绝对路径。', 'The project directory must be an absolute path.'],
    DIRECTORY_PATH_INVALID: ['项目目录格式无效。', 'The project directory is invalid.'],
    DIRECTORY_NOT_FOUND: ['找不到这个项目目录。', 'The project directory could not be found.'],
    DIRECTORY_NOT_A_DIRECTORY: ['选择的路径不是目录。', 'The selected path is not a directory.'],
    DIRECTORY_NOT_READABLE: ['无法读取这个项目目录。', 'The project directory could not be read.'],
    DIRECTORY_LIST_FAILED: ['读取项目目录失败，请稍后重试。', 'The project directory could not be listed. Try again.'],
    PROJECT_NOT_GIT: ['这个目录不是 Git 仓库。', 'This directory is not a Git repository.'],
    PROJECT_PATH_NOT_FOUND: ['找不到这个项目路径。', 'The project path could not be found.'],
    PROJECT_PATH_INVALID: ['项目路径无效。', 'The project path is invalid.'],
    PROJECT_PATH_MISSING: ['请输入项目路径。', 'Enter a project path.'],
    PROJECT_PATH_ABSOLUTE_REQUIRED: ['项目路径必须是本机绝对路径。', 'The project path must be an absolute path on this machine.'],
    PROJECT_PATH_TOO_LARGE: ['项目路径不能超过 4096 个字符。', 'The project path cannot exceed 4,096 characters.'],
    PROJECT_NAME_MISSING: ['请输入项目名称。', 'Enter a project name.'],
    PROJECT_NAME_TOO_LARGE: ['项目名称不能超过 200 个字符。', 'The project name cannot exceed 200 characters.'],
    PROJECT_PARENT_REQUIRED: ['请选择一个父目录来创建项目。', 'Choose a parent directory for the new project.'],
    PROJECT_PARENT_NOT_FOUND: ['新项目的父目录必须已经存在。', 'The parent directory for a new project must already exist.'],
    PROJECT_PARENT_INVALID: ['新项目的父目录不可用或包含符号链接。', 'The parent directory is unavailable or contains a symbolic link.'],
    PROJECT_PARENT_NOT_WRITABLE: ['没有权限在此父目录创建项目。', 'You do not have permission to create a project in this parent directory.'],
    PROJECT_TARGET_INVALID: ['新项目目录不可用。', 'The new project directory is not usable.'],
    PROJECT_TARGET_NOT_EMPTY: ['新项目目录必须为空。', 'The new project directory must be empty.'],
    PROJECT_DIRECTORY_CREATE_FAILED: ['无法创建新项目目录，请检查路径和权限。', 'The new project directory could not be created. Check the path and permissions.'],
    PROJECT_GIT_INIT_FAILED: ['无法初始化 Git，请确认 Git 已安装且目录可写。', 'Git could not be initialized. Check that Git is installed and the directory is writable.'],
    PROJECT_DATA_ROOT_OVERLAP: ['项目目录不能包含 Offcanon 数据目录。', 'The project directory cannot contain Offcanon data.'],
    MODEL_NOT_CONFIGURED: ['模型服务尚未配置，请打开设置完成配置。', 'The model service is not configured. Open Settings to finish setup.'],
    MODEL_ENDPOINT_INVALID: ['模型 Endpoint 必须是有效的 HTTP(S) 地址。', 'The model endpoint must be a valid HTTP(S) URL.'],
    MODEL_REQUEST_FAILED: ['模型服务拒绝了请求，请检查连接设置。', 'The model service rejected the request. Check the connection settings.'],
    MODEL_TRANSIENT_FAILURE: ['模型服务暂时不可用，请稍后重试。', 'The model service is temporarily unavailable. Try again later.'],
    MODEL_CONNECTION_FAILED: ['无法连接模型服务，请检查 Endpoint、模型名和 API key。', 'Could not connect to the model service. Check the endpoint, model, and API key.'],
    DIFF_UNAVAILABLE: ['这个实验的临时工作区已清理，变更详情不可用。', 'This experiment workspace was cleaned up, so its change details are unavailable.'],
    DIFF_WORKSPACE_MISSING: ['实验工作区不存在，无法读取变更。', 'The experiment workspace is missing, so its changes cannot be read.'],
    DIFF_READ_FAILED: ['读取实验工作区变更失败，请稍后重试。', 'The experiment workspace could not be read. Try again later.'],
    DIFF_TOO_LARGE: ['项目文件数量过大，无法生成完整差异。请先清理依赖或构建目录后重试。', 'The project is too large to generate a complete diff. Clean dependency or build directories and try again.'],
    DIFF_SNAPSHOT_PROJECT_MISMATCH: ['差异快照与实验所属项目不一致，无法显示变更。', 'The diff snapshot belongs to a different project, so changes cannot be displayed.'],
    DIFF_SYMLINK_BLOCKED: ['差异工作区包含不支持的符号链接，无法显示变更。', 'The diff workspace contains an unsupported symbolic link, so changes cannot be displayed.'],
    PROJECT_NOT_FOUND: ['项目已不存在，请刷新项目列表。', 'The project is no longer available. Refresh the project list.'],
    PROJECT_PATH_IMMUTABLE: ['项目路径不能修改；请重新打开正确的仓库。', 'A project path cannot be changed; reopen the correct repository.'],
    VERIFICATION_POLICY_LOCKED: ['项目有正在运行、验收或应用中的实验，暂时不能修改验收命令；已完成的结果会在修改后回到待重验。', 'Acceptance commands cannot change while a run, verification, or application is active; completed results return to re-verification when the policy changes.'],
    EXPERIMENT_NOT_REVERIFIABLE: ['这个实验当前不能重新验收。', 'This experiment cannot be verified again in its current state.'],
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
    MALFORMED_REQUEST: ['请求格式无法识别。', 'The request could not be parsed.'],
    METHOD_NOT_ALLOWED: ['此操作不支持当前请求方式。', 'This action does not support that request method.'],
    UNSUPPORTED_MEDIA_TYPE: ['请求内容类型不受支持。', 'The request content type is not supported.'],
    MODEL_API_KEY_INVALID: ['模型 API key 只能包含可发送的 ASCII 字符，且不能超过 4096 个字符。', 'The model API key must use printable ASCII characters and be 4,096 characters or fewer.'],
    EXPORT_TOO_LARGE: ['历史记录过大，暂时无法一次性导出；请减少历史范围后再试。', 'The history is too large for one export. Reduce the history scope and try again.'],
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
    MODEL_REQUEST_INVALID: ['模型请求格式无效。', 'The model request format is invalid.'],
    MODEL_REQUEST_FAILED: ['模型服务拒绝了请求。', 'The model service rejected the request.'],
    MODEL_TRANSIENT_FAILURE: ['模型服务暂时不可用。', 'The model service is temporarily unavailable.'],
    MODEL_CONNECTION_FAILED: ['连接模型服务失败。', 'The model connection failed.'],
  }
  const message = messages[code]
  const locale = typeof document !== 'undefined' ? document.documentElement.lang : 'zh-CN'
  return message ? (locale === 'zh-CN' ? message[0] : message[1]) : (locale === 'zh-CN' ? zhFallback : enFallback)
}
