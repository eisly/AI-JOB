import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/login/LoginView.vue'
import RegisterView from '@/views/login/RegisterView.vue'
import ForgotPasswordView from '@/views/login/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/login/ResetPasswordView.vue'
import HomeView from '@/views/HomeView.vue'
import DashboardView from '@/views/dashboard/DashboardView.vue'
import ProfileView from '@/views/profile/ProfileView.vue'
import ResumeView from '@/views/resume/ResumeView.vue'
import JobMatchView from '@/views/job-match/JobMatchView.vue'
import TrainingView from '@/views/training/TrainingView.vue'
import ProjectView from '@/views/project/ProjectView.vue'
import InterviewView from '@/views/interview/InterviewView.vue'
import GrowthView from '@/views/growth/GrowthView.vue'
import ApplicationView from '@/views/application/ApplicationView.vue'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录', public: true },
  },
  {
    path: '/register',
    name: 'register',
    component: RegisterView,
    meta: { title: '注册', public: true },
  },
  {
    path: '/forgot-password',
    name: 'forgot-password',
    component: ForgotPasswordView,
    meta: { title: '找回密码', public: true },
  },
  {
    path: '/reset-password',
    name: 'reset-password',
    component: ResetPasswordView,
    meta: { title: '重置密码', public: true },
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { title: '求职工作台' } },
      { path: 'profile', name: 'profile', component: ProfileView, meta: { title: '求职画像' } },
      { path: 'resume', name: 'resume', component: ResumeView, meta: { title: '简历中心' } },
      { path: 'job-match', name: 'job-match', component: JobMatchView, meta: { title: '岗位匹配' } },
      { path: 'training', name: 'training', component: TrainingView, meta: { title: '专项训练' } },
      { path: 'project', name: 'project', component: ProjectView, meta: { title: '项目实战', badge: '规划中' } },
      { path: 'interview', name: 'interview', component: InterviewView, meta: { title: '模拟面试' } },
      { path: 'growth', name: 'growth', component: GrowthView, meta: { title: '成长中心' } },
      { path: 'application', name: 'application', component: ApplicationView, meta: { title: '投递进度', badge: '方案预览' } },
      { path: 'account', name: 'account', component: HomeView, meta: { title: '账号设置' } },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]