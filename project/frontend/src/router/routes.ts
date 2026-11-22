import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/login/LoginView.vue'
import DashboardView from '@/views/dashboard/DashboardView.vue'
import ProfileView from '@/views/profile/ProfileView.vue'
import ResumeView from '@/views/resume/ResumeView.vue'
import ResumeOptimizationView from '@/views/resume-optimization/ResumeOptimizationView.vue'
import JobMatchView from '@/views/job-match/JobMatchView.vue'
import TrainingView from '@/views/training/TrainingView.vue'
import TrainingSessionView from '@/views/training/TrainingSessionView.vue'
import PracticePrepareView from '@/views/training/PracticePrepareView.vue'
import ProjectView from '@/views/project/ProjectView.vue'
import InterviewView from '@/views/interview/InterviewView.vue'
import VoiceInterviewView from '@/views/interview/VoiceInterviewView.vue'
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
  { path: '/forgot-password', name: 'forgot-password', component: () => import('@/views/login/ForgotPasswordView.vue'), meta: { title: '找回密码' } },
  // 旧版独立答题页地址：统一重定向到主框架内的答题页面
  { path: '/training/session/:setId', redirect: to => ({ name: 'training-practice', params: { setId: to.params.setId }, query: { legacy: '1' } }) },
  { path: '/reset-password', name: 'reset-password', component: () => import('@/views/login/ResetPasswordView.vue'), meta: { title: '重置密码' } },
  { path: '/register', name: 'register', component: () => import('@/views/login/RegisterView.vue'), meta: { title: '注册' } },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { title: '求职工作台' } },
      { path: 'profile', name: 'profile', component: ProfileView, meta: { title: '求职画像' } },
      { path: 'resume', name: 'resume', component: ResumeView, meta: { title: '简历中心' } },
      { path: 'resume-optimization', name: 'resume-optimization', component: ResumeOptimizationView, meta: { title: '智能简历优化' } },
      { path: 'jobs', name: 'jobs', component: JobMatchView, meta: { title: '岗位推荐与投递' } },
      { path: 'job-match', name: 'job-match', redirect: to => ({ name: 'jobs', query: { tab: to.query.matchId ? 'matches' : 'trusted', ...to.query }, hash: to.hash }) },
      { path: 'job-search', name: 'job-search', redirect: to => ({ name: 'jobs', query: { ...to.query, tab: 'search' }, hash: to.hash }) },
      { path: 'training', name: 'training', component: TrainingView, meta: { title: '专项训练' } },
      // 专项训练题目页：保留主框架（侧边栏/顶栏）骨架，新增独立答题页面
      { path: 'training/practice/:setId', name: 'training-practice', component: TrainingSessionView, meta: { title: '专项训练 · 题目' } },
      // 题目生成中页面：任务完成后自动替换为答题页面
      { path: 'training/prepare/:taskId', name: 'training-prepare', component: PracticePrepareView, meta: { title: '专项训练 · 生成题目' } },
      { path: 'project', name: 'project', component: ProjectView, meta: { title: '项目实战' } },
      { path: 'interview', name: 'interview', component: InterviewView, meta: { title: '模拟面试' } },
      { path: 'interview/voice', name: 'interview-voice', component: VoiceInterviewView, meta: { title: '语音模拟面试' } },
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
