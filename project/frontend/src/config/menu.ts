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
  MagicStick,
  Bell,
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
  { path: '/resume-optimization', name: 'resume-optimization', title: '智能简历优化', icon: MagicStick },
  { path: '/jobs', name: 'jobs', title: '岗位推荐与投递', icon: Aim },
  { path: '/training', name: 'training', title: '专项训练', icon: Edit },
  { path: '/project', name: 'project', title: '项目实战', icon: School },
  { path: '/interview', name: 'interview', title: '模拟面试', icon: Microphone },
  { path: '/growth', name: 'growth', title: '成长中心', icon: TrendCharts },
  { path: '/application', name: 'application', title: '投递进度', icon: List },
  { path: '/tasks', name: 'tasks', title: '任务中心', icon: Bell },
]