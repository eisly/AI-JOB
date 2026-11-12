<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useWorkbench } from '@/features/workbench/useWorkbench'
import { buildStageDescription } from '@/features/workbench/model/greeting'
import WelcomeHero from './components/WelcomeHero.vue'
import GoalSummary from './components/GoalSummary.vue'
import NextTaskCard from './components/NextTaskCard.vue'
import TargetJobsPanel from './components/TargetJobsPanel.vue'
import ProgressPanel from './components/ProgressPanel.vue'
import ActivityFeed from './components/ActivityFeed.vue'

const { state, refresh, retryRegion } = useWorkbench()

const hasProfile = computed(() => !!state.goal.data && !state.goal.data.isEmpty)
const description = computed(() => buildStageDescription(state.goal.data?.candidateTypeLabel, hasProfile.value))
const identityError = computed(() => (state.identity.status === 'ERROR' ? state.identity.error : undefined))

onMounted(refresh)
</script>

<template>
  <div class="dashboard">
    <section class="welcome">
      <WelcomeHero
        :nickname="state.identity.data?.nickname"
        :description="description"
        :refreshed-at="state.refreshedAt"
        :refreshing="state.refreshing"
        :has-profile="hasProfile"
        :identity-error="identityError"
        @refresh="refresh"
      />
      <GoalSummary
        :summary="state.goal.data"
        :status="state.goal.status"
        :error="state.goal.error"
        @retry="retryRegion('goal')"
      />
    </section>

    <div class="overview">
      <NextTaskCard
        :model="state.nextTask.data"
        :status="state.nextTask.status"
        :error="state.nextTask.error"
        @retry="retryRegion('nextTask')"
      />
      <TargetJobsPanel
        :model="state.targetJobs.data"
        :status="state.targetJobs.status"
        :error="state.targetJobs.error"
        @retry="retryRegion('targetJobs')"
      />
    </div>

    <ProgressPanel
      class="progress-panel"
      :model="state.progress.data"
      :status="state.progress.status"
      :error="state.progress.error"
      @retry="retryRegion('progress')"
    />
    <ActivityFeed
      class="activity-panel"
      :items="state.activities.data"
      :status="state.activities.status"
      :error="state.activities.error"
      @retry="retryRegion('activities')"
    />
  </div>
</template>

<style scoped lang="scss">
.dashboard { max-width: 1500px; margin: 0 auto; color: #0b1746; }
.welcome { padding: 32px 14px 20px; }
.overview { display: grid; grid-template-columns: minmax(0, 1.37fr) minmax(0, 1fr); gap: 12px; }
.progress-panel, .activity-panel { margin-top: 12px; }
@media (max-width: 1050px) {
  .overview { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 700px) {
  .welcome { padding-left: 0; padding-right: 0; }
}
</style>
