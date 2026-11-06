import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/login/LoginView.vue'
import DashboardView from '@/views/dashboard/DashboardView.vue'
import ProfileView from '@/views/profile/ProfileView.vue'
import ResumeView from '@/views/resume/ResumeView.vue'
import ResumeOptimizationView from '@/views/resume-optimization/ResumeOptimizationView.vue'
import JobMatchView from '@/views/job-match/JobMatchView.vue'
import JobSearchView from '@/views/job-search/JobSearchView.vue'
import TrainingView from '@/views/training/TrainingView.vue'
import ProjectView from '@/views/project/ProjectView.vue'
import InterviewView from '@/views/interview/InterviewView.vue'
import GrowthView from '@/views/growth/GrowthView.vue'
import ApplicationView from '@/views/application/ApplicationView.vue'
import TasksView from '@/views/tasks/TasksView.vue'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录' },
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { title: '求职工作台' } },
      { path: 'profile', name: 'profile', component: ProfileView, meta: { title: '求职画像' } },
      { path: 'resume', name: 'resume', component: ResumeView, meta: { title: '简历中心' } },
      { path: 'resume-optimization', name: 'resume-optimization', component: ResumeOptimizationView, meta: { title: '智能简历优化' } },
      { path: 'job-match', name: 'job-match', component: JobMatchView, meta: { title: '岗位匹配' } },
      { path: 'job-search', name: 'job-search', component: JobSearchView, meta: { title: '联网搜索' } },
      { path: 'training', name: 'training', component: TrainingView, meta: { title: '专项训练' } },
      { path: 'project', name: 'project', component: ProjectView, meta: { title: '项目实战' } },
      { path: 'interview', name: 'interview', component: InterviewView, meta: { title: '模拟面试' } },
      { path: 'growth', name: 'growth', component: GrowthView, meta: { title: '成长中心' } },
      { path: 'application', name: 'application', component: ApplicationView, meta: { title: '投递进度' } },
      { path: 'tasks', name: 'tasks', component: TasksView, meta: { title: '任务中心' } },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]