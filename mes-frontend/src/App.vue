<script setup lang="ts">
import Watermark from './components/Watermark.vue'
import ForcePasswordDialog from './components/ForcePasswordDialog.vue'
</script>

<template>
  <Watermark />
  <router-view />
  <!-- 初始口令没改之前，后端会拦住业务接口，这个框负责让用户就地改掉 -->
  <ForcePasswordDialog />
</template>
