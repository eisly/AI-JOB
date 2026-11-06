<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown, Check, Close, Search } from '@element-plus/icons-vue'
import {
  filterJobRoleGroups,
  findJobRoleByLabel,
  findJobRoleByValue,
  isPresetJobRoleValue,
  JOB_ROLE_GROUPS,
  MAX_JOB_ROLE_LENGTH,
  type JobRoleOption,
} from '@/config/jobRoles'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  disabled?: boolean
}>(), {
  placeholder: '搜索或选择目标岗位',
  disabled: false,
})

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'change', value: string): void
}>()

const visible = ref(false)
const keyword = ref('')
const activeGroupIndex = ref(0)
const activeIndex = ref(-1)
const searchRef = ref()

const popperOptions = {
  modifiers: [
    { name: 'preventOverflow', options: { padding: 8 } },
    { name: 'flip', options: { padding: 8 } },
  ],
}

const selectedRole = computed(() => {
  if (!props.modelValue) return undefined
  return findJobRoleByLabel(props.modelValue) ?? findJobRoleByValue(props.modelValue)
})

const displayValue = computed(() => selectedRole.value?.label ?? props.modelValue)

const searchGroups = computed(() => filterJobRoleGroups(keyword.value))

const searchResults = computed(() =>
  searchGroups.value.flatMap((group) => group.roles.map((role) => ({ role, group: group.label }))),
)

const customOption = computed(() => {
  const value = keyword.value.trim()
  if (!value || value.length > MAX_JOB_ROLE_LENGTH) return ''
  if (isPresetJobRoleValue(value)) return ''
  if (findJobRoleByLabel(value)) return ''
  return value
})

const tooLong = computed(() => keyword.value.trim().length > MAX_JOB_ROLE_LENGTH)

const currentGroup = computed(() => JOB_ROLE_GROUPS[activeGroupIndex.value] ?? JOB_ROLE_GROUPS[0])

const navigationOptions = computed<JobRoleOption[]>(() => currentGroup.value?.roles ?? [])

const keyboardOptions = computed<JobRoleOption[]>(() => {
  if (isSearching.value) {
    const options = searchResults.value.map((item) => item.role)
    if (customOption.value) {
      options.push({ value: customOption.value, label: customOption.value, keywords: [] })
    }
    return options
  }
  return navigationOptions.value
})

const isSearching = computed(() => Boolean(keyword.value.trim()))

const customIsActive = computed(
  () => isSearching.value && customOption.value !== '' && activeIndex.value === searchResults.value.length,
)

const isRoleSelected = (role: JobRoleOption) => role.label === props.modelValue

const close = () => {
  visible.value = false
  keyword.value = ''
  activeIndex.value = -1
}

const open = () => {
  if (props.disabled) return
  visible.value = true
}

const selectRole = (role: JobRoleOption) => {
  emit('update:modelValue', role.label)
  emit('change', role.label)
  close()
}

const selectCustom = () => {
  if (!customOption.value) return
  emit('update:modelValue', customOption.value)
  emit('change', customOption.value)
  close()
}

const selectGroup = (index: number) => {
  activeGroupIndex.value = index
  activeIndex.value = -1
}

const selectActive = () => {
  const option = keyboardOptions.value[activeIndex.value]
  if (option) {
    selectRole(option)
    return
  }
  if (isSearching.value && searchResults.value.length) {
    selectRole(searchResults.value[0].role)
    return
  }
  if (customOption.value) {
    selectCustom()
  }
}

const handleKeydown = (event: Event | KeyboardEvent) => {
  if (!(event instanceof KeyboardEvent)) return

  if (event.key === 'Escape') {
    if (visible.value) {
      event.preventDefault()
      close()
    }
    return
  }

  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!visible.value) {
      open()
      return
    }
    const total = keyboardOptions.value.length
    if (!total) return
    event.preventDefault()
    activeIndex.value = event.key === 'ArrowDown'
      ? (activeIndex.value + 1) % total
      : (activeIndex.value - 1 + total) % total
    return
  }

  if (event.key === 'Enter') {
    if (!visible.value) {
      open()
      return
    }
    event.preventDefault()
    selectActive()
    return
  }

  if (!isSearching.value && event.key === 'ArrowRight') {
    event.preventDefault()
    selectGroup((activeGroupIndex.value + 1) % JOB_ROLE_GROUPS.length)
    return
  }

  if (!isSearching.value && event.key === 'ArrowLeft') {
    event.preventDefault()
    selectGroup((activeGroupIndex.value - 1 + JOB_ROLE_GROUPS.length) % JOB_ROLE_GROUPS.length)
  }
}

const clearValue = () => {
  emit('update:modelValue', '')
  emit('change', '')
  close()
}

const handleDocumentKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && visible.value) {
    close()
  }
}

watch(visible, (value) => {
  if (value) {
    keyword.value = ''
    activeIndex.value = -1
    const groupIndex = selectedRole.value
      ? JOB_ROLE_GROUPS.findIndex((group) =>
        group.roles.some((role) => role.value === selectedRole.value?.value))
      : 0
    activeGroupIndex.value = Math.max(groupIndex, 0)
    nextTick(() => searchRef.value?.focus?.())
  } else {
    keyword.value = ''
    activeIndex.value = -1
  }
})

onMounted(() => document.addEventListener('keydown', handleDocumentKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleDocumentKeydown))
</script>

<template>
  <el-popover
    v-model:visible="visible"
    :width="560"
    placement="bottom-start"
    trigger="click"
    :show-arrow="false"
    :teleported="true"
    :popper-options="popperOptions"
    popper-class="job-role-cascader__popper"
  >
    <template #reference>
      <el-input
        :model-value="displayValue"
        :placeholder="placeholder"
        :disabled="disabled"
        readonly
        aria-haspopup="listbox"
        :aria-expanded="visible"
        @keydown="handleKeydown"
      >
        <template #suffix>
          <el-icon
            v-if="displayValue && !disabled"
            class="job-role-cascader__clear"
            @click.stop="clearValue"
          >
            <Close />
          </el-icon>
          <el-icon class="job-role-cascader__arrow"><ArrowDown /></el-icon>
        </template>
      </el-input>
    </template>

    <div class="job-role-cascader" role="dialog" aria-label="目标岗位选择">
      <div class="job-role-cascader__search">
        <el-input
          ref="searchRef"
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索岗位，如 Java、Agent、RAG"
          clearable
          @keydown="handleKeydown"
        />
      </div>

      <div v-if="isSearching" class="job-role-cascader__results" role="listbox" aria-label="搜索结果">
        <template v-if="searchResults.length">
          <div class="job-role-cascader__section-label">搜索结果</div>
          <button
            v-for="(item, index) in searchResults"
            :key="item.role.value"
            type="button"
            class="job-role-cascader__option"
            :class="{ 'is-active': activeIndex === index, 'is-selected': isRoleSelected(item.role) }"
            role="option"
            :aria-selected="isRoleSelected(item.role)"
            @mouseenter="activeIndex = index"
            @click="selectRole(item.role)"
          >
            <span class="job-role-cascader__option-label">{{ item.role.label }}</span>
            <span class="job-role-cascader__option-group">{{ item.group }}</span>
          </button>
        </template>

        <button
          v-if="customOption"
          type="button"
          class="job-role-cascader__option job-role-cascader__option--custom"
          :class="{ 'is-active': customIsActive }"
          role="option"
          @mouseenter="activeIndex = searchResults.length"
          @click="selectCustom"
        >
          <span class="job-role-cascader__option-label">+ 使用自定义岗位：{{ customOption }}</span>
        </button>

        <div v-if="tooLong" class="job-role-cascader__hint">
          岗位名称不能超过 {{ MAX_JOB_ROLE_LENGTH }} 个字符
        </div>
        <div v-if="!searchResults.length && !customOption && !tooLong" class="job-role-cascader__empty">
          没有匹配的预设岗位
        </div>
      </div>

      <div v-else class="job-role-cascader__body">
        <div class="job-role-cascader__groups" role="tablist" aria-label="岗位分类">
          <button
            v-for="(group, index) in JOB_ROLE_GROUPS"
            :key="group.label"
            type="button"
            class="job-role-cascader__group"
            :class="{ 'is-active': index === activeGroupIndex }"
            role="tab"
            :aria-selected="index === activeGroupIndex"
            @mouseenter="selectGroup(index)"
            @click="selectGroup(index)"
          >
            {{ group.label }}
          </button>
        </div>

        <div class="job-role-cascader__roles" role="listbox" aria-label="岗位列表">
          <button
            v-for="(role, index) in navigationOptions"
            :key="role.value"
            type="button"
            class="job-role-cascader__option"
            :class="{ 'is-active': activeIndex === index, 'is-selected': isRoleSelected(role) }"
            role="option"
            :aria-selected="isRoleSelected(role)"
            @mouseenter="activeIndex = index"
            @click="selectRole(role)"
          >
            <span class="job-role-cascader__option-label">{{ role.label }}</span>
            <el-icon v-if="isRoleSelected(role)" class="job-role-cascader__check"><Check /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<style lang="scss">
.job-role-cascader__popper {
  padding: 0 !important;
  border-radius: 10px !important;
  overflow: hidden;
  max-width: calc(100vw - 24px);
}
</style>

<style scoped lang="scss">
.job-role-cascader__clear {
  margin-right: 4px;
  color: $ai-text-tertiary;
  cursor: pointer;

  &:hover {
    color: $ai-text-secondary;
  }
}

.job-role-cascader__arrow {
  color: $ai-text-tertiary;
}

.job-role-cascader {
  display: flex;
  flex-direction: column;
  max-height: 420px;
  background-color: $ai-bg-card;

  &__search {
    padding: 12px;
    border-bottom: 1px solid $ai-border-light;
  }

  &__results {
    max-height: 340px;
    overflow-y: auto;
    padding: 6px;
  }

  &__section-label {
    padding: 6px 10px;
    font-size: 12px;
    color: $ai-text-tertiary;
  }

  &__body {
    display: flex;
    min-height: 260px;
    max-height: 340px;
  }

  &__groups {
    width: 158px;
    flex-shrink: 0;
    padding: 6px;
    overflow-y: auto;
    border-right: 1px solid $ai-border-light;
  }

  &__group {
    display: block;
    width: 100%;
    padding: 10px 12px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: $ai-text-secondary;
    font-size: 13px;
    text-align: left;
    cursor: pointer;

    &:hover {
      background-color: $ai-bg-light;
      color: $ai-text-primary;
    }

    &.is-active {
      background-color: $ai-primary;
      color: #fff;
      font-weight: 600;
    }
  }

  &__roles {
    flex: 1;
    min-width: 0;
    padding: 6px;
    overflow-y: auto;
  }

  &__option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    width: 100%;
    padding: 10px 12px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: $ai-text-primary;
    font-size: 13px;
    text-align: left;
    cursor: pointer;

    &:hover,
    &.is-active {
      background-color: $ai-primary-light;
    }

    &.is-selected {
      color: $ai-primary;
      font-weight: 600;
    }

    &--custom {
      color: $ai-primary;

      .job-role-cascader__option-label {
        white-space: normal;
      }
    }

    &:focus-visible,
    &.job-role-cascader__group:focus-visible {
      outline: 2px solid $ai-primary;
      outline-offset: -2px;
    }
  }

  &__option-label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__option-group {
    flex-shrink: 0;
    font-size: 12px;
    color: $ai-text-tertiary;
  }

  &__check {
    color: $ai-primary;
    flex-shrink: 0;
  }

  &__hint,
  &__empty {
    padding: 10px 12px;
    font-size: 12px;
    color: $ai-text-tertiary;
  }
}

.job-role-cascader__group:focus-visible {
  outline: 2px solid $ai-primary;
  outline-offset: -2px;
}

@media (max-width: 640px) {
  .job-role-cascader {
    &__body {
      flex-direction: column;
      min-height: 0;
      max-height: 340px;
    }

    &__groups {
      display: flex;
      width: 100%;
      overflow-x: auto;
      overflow-y: hidden;
      border-right: 0;
      border-bottom: 1px solid $ai-border-light;
    }

    &__group {
      width: auto;
      flex-shrink: 0;
      white-space: nowrap;
    }

    &__roles {
      max-height: 240px;
    }
  }
}
</style>