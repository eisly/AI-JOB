<script setup lang="ts">
import { Aim, Briefcase, Location, School, Clock, TrendCharts, ArrowRight } from '@element-plus/icons-vue'
</script>

<template>
  <div class="dashboard">
    <section class="welcome">
      <p>你好，欢迎回来！</p>
      <h1>今天，继续你的求职准备</h1>
      <p class="welcome__description">基于你的求职目标，规划训练与实战任务，稳步提升竞争力。</p>
      <div class="goals">
        <span><el-icon><Briefcase /></el-icon>求职方向 <strong>未设置</strong></span>
        <span><el-icon><Location /></el-icon>目标城市 <strong>未设置</strong></span>
        <span><el-icon><School /></el-icon>求职阶段 <strong>未设置</strong></span>
      </div>
    </section>

    <div class="overview">
      <section class="panel task-panel">
        <h2><el-icon><Aim /></el-icon>你的下一项任务</h2>
        <div class="empty-state empty-state--blue">
          <strong>暂无训练任务</strong>
          <p>完善求职画像后，开始你的求职准备。</p>
          <router-link class="primary-button" to="/profile">完善求职画像 <el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </section>
      <section class="panel job-panel">
        <div class="panel__heading"><h2><el-icon><Aim /></el-icon>近期目标岗位</h2><router-link to="/job-match">查看岗位匹配 <el-icon><ArrowRight /></el-icon></router-link></div>
        <div class="empty-state empty-state--border"><strong>暂无目标岗位</strong><p>设置求职方向后，查看适合你的岗位。</p></div>
      </section>
    </div>

    <section class="panel progress-panel">
      <div class="panel__heading"><h2><el-icon><TrendCharts /></el-icon>求职进度 <small>完成各阶段任务，逐步提升你的求职竞争力。</small></h2><router-link to="/growth">查看详细进度 <el-icon><ArrowRight /></el-icon></router-link></div>
      <div class="empty-line">暂无求职进度</div>
    </section>
    <section class="panel activity-panel">
      <div class="panel__heading"><h2><el-icon><Clock /></el-icon>最新动态</h2><router-link to="/growth">查看全部记录 <el-icon><ArrowRight /></el-icon></router-link></div>
      <div class="empty-line empty-line--border">暂无动态记录</div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.dashboard { max-width: 1500px; margin: 0 auto; color: #0b1746; }
.welcome { padding: 0 14px 20px; color: #61749b; font-size: 16px; }
.welcome h1 { color: #09143e; font-size: 30px; line-height: 1.3; margin: 8px 0; font-weight: 750; }
.welcome__description { font-size: 17px; }
.goals { display: flex; flex-wrap: wrap; gap: 0; margin-top: 20px; }
.goals span { display: flex; gap: 10px; align-items: center; padding: 0 28px; border-right: 1px solid #dce6f5; }
.goals span:first-child { padding-left: 0; } .goals span:last-child { border: 0; }
.goals .el-icon { font-size: 21px; color: #122357; } .goals strong { color: #101d4b; }
.overview { display: grid; grid-template-columns: 1.37fr 1fr; gap: 12px; }
.panel { background: #fff; border: 1px solid #e8f0fa; border-radius: 10px; padding: 16px 18px 12px; }
.panel h2 { font-size: 18px; display: flex; align-items: center; gap: 10px; line-height: 1.4; }
.panel h2 > .el-icon { color: #0861fc; font-size: 23px; }
.panel__heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.panel__heading a { display: inline-flex; align-items: center; white-space: nowrap; color: #0662ff; font-size: 14px; }
.task-panel > h2 { margin-bottom: 14px; }
.empty-state { min-height: 228px; border-radius: 9px; padding: 26px; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 12px; }
.empty-state--blue { background: #ecf5ff; }.empty-state--border { border: 1px solid #e5eef9; align-items: center; text-align: center; }
.empty-state strong { font-size: 18px; }.empty-state p { color: #7184a6; line-height: 1.5; }
.primary-button { display: inline-flex; align-items: center; justify-content: center; gap: 10px; padding: 11px 20px; margin-top: 8px; background: #0c5cf5; color: white; border-radius: 7px; font-weight: 600; }.primary-button:hover { background: #084ed5; }
.progress-panel, .activity-panel { margin-top: 12px; }.progress-panel h2 small { color: #7284a5; font-size: 14px; font-weight: 400; margin-left: 8px; }
.empty-line { display: grid; place-items: center; min-height: 105px; color: #8495b1; }.empty-line--border { border: 1px solid #e7eff9; border-radius: 8px; }
@media (max-width: 1050px) { .overview { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .welcome { padding-left: 0; }.welcome h1 { font-size: 24px; }.goals { gap: 10px; }.goals span { padding: 0 12px 0 0; }.progress-panel h2 small { display: none; } }
</style>
