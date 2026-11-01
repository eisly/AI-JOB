import type { Component } from 'vue'
import {
  House,
  User,
  Document,
  Aim,
  Edit,
  School,
  Microphone,
  TrendCharts,
  List,
} from '@element-plus/icons-vue'

export interface MenuItem {
  path: string
  name: string
  title: string
  icon: Component
  badge?: string
}

export const menuItems: MenuItem[] = [
  { path: '/dashboard', name: 'dashboard', title: '求职工作台', icon: House },
  { path: '/profile', name: 'profile', title: '求职画像', icon: User },
  { path: '/resume', name: 'resume', title: '简历中心', icon: Document },
  { path: '/job-match', name: 'job-match', title: '岗位匹配', icon: Aim },
  { path: '/training', name: 'training', title: '专项训练', icon: Edit },
  { path: '/project', name: 'project', title: '项目实战', icon: School, badge: '规划中' },
  { path: '/interview', name: 'interview', title: '模拟面试', icon: Microphone },
  { path: '/growth', name: 'growth', title: '成长中心', icon: TrendCharts },
  { path: '/application', name: 'application', title: '投递进度', icon: List, badge: '方案预览' },
]