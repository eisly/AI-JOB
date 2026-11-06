export interface JobRoleOption {
  value: string
  label: string
  keywords: string[]
}

export interface JobRoleGroup {
  label: string
  roles: JobRoleOption[]
}

export const MAX_JOB_ROLE_LENGTH = 32

export const JOB_ROLE_GROUPS: JobRoleGroup[] = [
  {
    label: '后端开发',
    roles: [
      { value: 'JAVA_BACKEND', label: 'Java 后端开发', keywords: ['java', '后端', 'backend', '服务端'] },
      { value: 'PYTHON_BACKEND', label: 'Python 后端开发', keywords: ['python', '后端', 'backend'] },
      { value: 'GO_BACKEND', label: 'Go 后端开发', keywords: ['go', 'golang', '后端', 'backend'] },
      { value: 'CPP_DEVELOPER', label: 'C++ 开发', keywords: ['c++', 'cpp', 'c', '开发'] },
      { value: 'NODEJS_BACKEND', label: 'Node.js 后端开发', keywords: ['node', 'nodejs', 'node.js', '后端', 'backend'] },
      { value: 'FULL_STACK', label: '全栈开发', keywords: ['全栈', 'fullstack', 'full-stack'] },
      { value: 'MICROSERVICE_DEVELOPER', label: '微服务开发工程师', keywords: ['微服务', 'microservice', '后端'] },
    ],
  },
  {
    label: 'AI / 大模型应用',
    roles: [
      { value: 'AI_APPLICATION_DEVELOPER', label: 'AI 应用开发工程师', keywords: ['ai', '人工智能', '应用开发'] },
      { value: 'AI_AGENT_DEVELOPER', label: 'AI Agent 开发工程师', keywords: ['ai', 'agent', '智能体', '大模型'] },
      { value: 'LLM_APPLICATION_DEVELOPER', label: '大模型应用开发工程师', keywords: ['大模型', 'llm', 'ai', '应用开发'] },
      { value: 'RAG_APPLICATION_DEVELOPER', label: 'RAG 应用开发工程师', keywords: ['rag', '检索增强', '大模型', 'ai'] },
      { value: 'LLM_ENGINEER', label: 'LLM 工程师', keywords: ['llm', '大模型', 'ai'] },
      { value: 'AI_PLATFORM_DEVELOPER', label: 'AI 平台开发工程师', keywords: ['ai', '平台', 'platform'] },
      { value: 'PROMPT_ENGINEER', label: 'Prompt 工程师', keywords: ['prompt', '提示词', 'ai'] },
      { value: 'AI_ALGORITHM_ENGINEER', label: 'AI 算法工程师', keywords: ['ai', '算法', 'algorithm'] },
    ],
  },
  {
    label: '前端 / 客户端',
    roles: [
      { value: 'FRONTEND', label: '前端开发工程师', keywords: ['前端', 'frontend', 'web'] },
      { value: 'REACT_DEVELOPER', label: 'React 开发工程师', keywords: ['react', '前端'] },
      { value: 'VUE_DEVELOPER', label: 'Vue 开发工程师', keywords: ['vue', '前端'] },
      { value: 'ANDROID_DEVELOPER', label: 'Android 开发工程师', keywords: ['android', '安卓', '客户端'] },
      { value: 'IOS_DEVELOPER', label: 'iOS 开发工程师', keywords: ['ios', '苹果', '客户端'] },
      { value: 'FLUTTER_DEVELOPER', label: 'Flutter 开发工程师', keywords: ['flutter', '跨端', '客户端'] },
    ],
  },
  {
    label: '算法 / 数据',
    roles: [
      { value: 'ALGORITHM', label: '算法工程师', keywords: ['算法', 'algorithm', 'ai'] },
      { value: 'MACHINE_LEARNING_ENGINEER', label: '机器学习工程师', keywords: ['机器学习', 'ml', 'machine learning'] },
      { value: 'DEEP_LEARNING_ENGINEER', label: '深度学习工程师', keywords: ['深度学习', 'dl', 'deep learning'] },
      { value: 'NLP_ALGORITHM_ENGINEER', label: 'NLP 算法工程师', keywords: ['nlp', '自然语言', '算法'] },
      { value: 'COMPUTER_VISION_ENGINEER', label: '计算机视觉工程师', keywords: ['cv', '计算机视觉', '视觉'] },
      { value: 'RECOMMENDATION_ALGORITHM', label: '推荐算法工程师', keywords: ['推荐', '推荐算法', 'recommendation'] },
      { value: 'DATA', label: '数据开发工程师', keywords: ['数据开发', '数仓', 'etl', '数据'] },
      { value: 'DATA_ANALYST', label: '数据分析师', keywords: ['数据分析', '分析师', 'data analyst'] },
      { value: 'DATA_ENGINEER', label: '数据工程师', keywords: ['数据工程', 'data engineer', '数据'] },
    ],
  },
  {
    label: '测试 / 运维 / 安全',
    roles: [
      { value: 'TEST', label: '软件测试工程师', keywords: ['测试', 'qa', 'test', '软件测试'] },
      { value: 'TEST_DEVELOPMENT_ENGINEER', label: '测试开发工程师', keywords: ['测试开发', 'test development', '自动化'] },
      { value: 'AUTOMATION_TEST_ENGINEER', label: '自动化测试工程师', keywords: ['自动化测试', 'automation', '测试'] },
      { value: 'DEVOPS_ENGINEER', label: 'DevOps 工程师', keywords: ['devops', '运维', 'ci/cd'] },
      { value: 'SRE_ENGINEER', label: 'SRE 工程师', keywords: ['sre', '运维', '可靠性'] },
      { value: 'CLOUD_ENGINEER', label: '云计算工程师', keywords: ['云计算', 'cloud', '云原生'] },
      { value: 'SECURITY_ENGINEER', label: '网络安全工程师', keywords: ['网络安全', '安全', 'security'] },
    ],
  },
  {
    label: '其他技术岗位',
    roles: [
      { value: 'EMBEDDED_ENGINEER', label: '嵌入式开发工程师', keywords: ['嵌入式', 'embedded', '硬件'] },
      { value: 'DATABASE_DEVELOPER', label: '数据库开发工程师', keywords: ['数据库', 'dba', 'database', 'sql'] },
      { value: 'GAME_DEVELOPER', label: '游戏开发工程师', keywords: ['游戏', 'game', 'unity', 'ue'] },
      { value: 'BLOCKCHAIN_DEVELOPER', label: '区块链开发工程师', keywords: ['区块链', 'blockchain', 'web3'] },
      { value: 'TECHNICAL_PRODUCT_MANAGER', label: '技术产品经理', keywords: ['产品经理', 'tpm', 'product manager'] },
      { value: 'SOLUTION_ENGINEER', label: '解决方案工程师', keywords: ['解决方案', '售前', 'solution'] },
    ],
  },
]

export const JOB_ROLE_LIST: JobRoleOption[] = JOB_ROLE_GROUPS.flatMap((group) => group.roles)

export const JOB_ROLE_MAP = new Map(JOB_ROLE_LIST.map((role) => [role.value, role]))

const JOB_ROLE_VALUE_SET = new Set(JOB_ROLE_LIST.map((role) => role.value))

const normalize = (value: string) => value.trim().toLowerCase()

export function getJobRoleLabel(value: string): string {
  return JOB_ROLE_MAP.get(value)?.label ?? value
}

export function isPresetJobRoleValue(value: string): boolean {
  return JOB_ROLE_VALUE_SET.has(value)
}

export function findJobRoleByLabel(label: string): JobRoleOption | undefined {
  const normalized = normalize(label)
  return JOB_ROLE_LIST.find((role) => normalize(role.label) === normalized)
}

export function findJobRoleByValue(value: string): JobRoleOption | undefined {
  return JOB_ROLE_MAP.get(value)
}

export function filterJobRoleGroups(query: string): JobRoleGroup[] {
  const keyword = normalize(query)
  if (!keyword) {
    return JOB_ROLE_GROUPS
  }
  return JOB_ROLE_GROUPS.map((group) => ({
    ...group,
    roles: group.roles.filter((role) =>
      [role.label, role.value, ...role.keywords].some((item) => normalize(item).includes(keyword)),
    ),
  })).filter((group) => group.roles.length > 0)
}