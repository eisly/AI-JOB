import type { JobSearchItemVO, JobVO } from '@/api/types'
import { educationLabels, formatSalary } from '@/utils/format'

const educationPattern = /学历不限|不限学历|(?:全日制\s*)?(?:统招\s*)?(?:高中|大专|专科|本科|硕士(?:研究生)?|博士(?:研究生)?|研究生)(?:\s*(?:及以上|以上|或以上))?/
const legacySalary = /(?<![\dA-Za-z./-])\d+(?:\.\d+)?\s*(?:(?:[kK千万元])?\s*[-–—~～至]\s*\d+(?:\.\d+)?\s*)?[kK千万元](?:\s*\/\s*(?:月|年|天|日|小时|时))?(?![\dA-Za-z])/

export function jobEducation(job: JobVO | JobSearchItemVO) {
  if ('jobId' in job) {
    const original = job.educationText?.trim() || ''
    const labelled = original.match(/(?:最低学历|学历要求|学历)\s*[:：]\s*(.+)/)?.[1]
    if (labelled?.startsWith('不限')) return '学历不限'
    return (labelled || original).match(educationPattern)?.[0]
      || (job.educationMin ? educationLabels[job.educationMin] : null)
  }
  return job.requirements?.educationMin ? educationLabels[job.requirements.educationMin] : null
}

export function jobSalary(job: JobVO | JobSearchItemVO) {
  if ('jobId' in job) {
    const salary = job.salaryText?.trim()
    if (salary && !/^(?:来源未提供|薪资未明确|未提供|[—-]+)$/.test(salary)) {
      const cleanSalary = educationPattern.test(salary) ? salary.match(legacySalary)?.[0].trim() : salary
      if (cleanSalary) return cleanSalary
    }
  }
  const { minMonthlySalaryCents: min, maxMonthlySalaryCents: max } = job
  if (min != null && max != null) return formatSalary(min, max)
  // 旧搜索结果可能将薪资拼接在学历要求之后。
  if ('jobId' in job && job.educationText) {
    const degree = educationPattern.exec(job.educationText)
    if (degree) return job.educationText.slice(degree.index + degree[0].length).match(legacySalary)?.[0]?.trim() || null
  }
  return null
}
