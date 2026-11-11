<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { api, fmtQty } from '../../api'

/**
 * 标签打印专用页（不带侧边栏与顶栏，直接跑在 #app 下），
 * 目的是让浏览器打印输出的就是标签纸本身：@page 尺寸按纸型注入，
 * 二维码用 <img> 承载并强制 print-color-adjust: exact，不依赖「背景图形」开关。
 */

type PresetKey = 'T40x30' | 'T50x30' | 'T60x40' | 'A4_24' | 'A4_12' | 'A4_2'

interface Preset {
  key: PresetKey
  name: string
  pageW: number
  pageH: number
  labelW: number
  labelH: number
  cols: number
  rows: number
  qr: number
  codeFs: number
  nameFs: number
  metaFs: number
  pad: number
}

const PRESETS: Preset[] = [
  { key: 'T40x30', name: '热敏标签 40×30mm', pageW: 40, pageH: 30, labelW: 40, labelH: 30, cols: 1, rows: 1, qr: 15, codeFs: 2.4, nameFs: 2.2, metaFs: 1.9, pad: 1.2 },
  { key: 'T50x30', name: '热敏标签 50×30mm', pageW: 50, pageH: 30, labelW: 50, labelH: 30, cols: 1, rows: 1, qr: 17, codeFs: 2.6, nameFs: 2.3, metaFs: 2, pad: 1.2 },
  { key: 'T60x40', name: '热敏标签 60×40mm', pageW: 60, pageH: 40, labelW: 60, labelH: 40, cols: 1, rows: 1, qr: 25, codeFs: 3, nameFs: 2.6, metaFs: 2.2, pad: 1.5 },
  { key: 'A4_24', name: 'A4 24 格（70×37mm）', pageW: 210, pageH: 297, labelW: 70, labelH: 37, cols: 3, rows: 8, qr: 25, codeFs: 2.9, nameFs: 2.5, metaFs: 2.1, pad: 1.5 },
  { key: 'A4_12', name: 'A4 12 格（70×74mm）', pageW: 210, pageH: 297, labelW: 70, labelH: 74, cols: 3, rows: 4, qr: 34, codeFs: 3.3, nameFs: 2.9, metaFs: 2.4, pad: 2 },
  { key: 'A4_2', name: 'A4 2 列大标签（105×74mm）', pageW: 210, pageH: 297, labelW: 105, labelH: 74, cols: 2, rows: 4, qr: 38, codeFs: 3.6, nameFs: 3.1, metaFs: 2.6, pad: 2 }
]

interface LabelItem {
  id: string
  kind: 'WO' | 'OP' | 'SN'
  tag: string
  main: string
  name: string
  meta: string
  url: string
}

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorText = ref('')
const work = ref<any>(null)
const unitTotal = ref(0)
const allItems = ref<LabelItem[]>([])

const presetKey = ref<PresetKey>((localStorage.getItem('mes:label-preset') as PresetKey) || 'T40x30')
const includeWork = ref(true)
const includeOps = ref(false)
const includeUnits = ref(true)
const copies = ref(1)
const offset = ref(0)

// 单件标签一次生成上限，避免千件工单把浏览器卡死
const UNIT_LABEL_LIMIT = 200

const preset = computed(() => PRESETS.find((p) => p.key === presetKey.value) || PRESETS[0])
const isSingle = computed(() => preset.value.cols * preset.value.rows === 1)
const workNo = computed(() => String(route.query.workNo || '').trim())

watch(presetKey, (key) => {
  localStorage.setItem('mes:label-preset', key)
  applyPageRule()
})

const counts = computed(() => ({
  WO: allItems.value.filter((item) => item.kind === 'WO').length,
  OP: allItems.value.filter((item) => item.kind === 'OP').length,
  SN: allItems.value.filter((item) => item.kind === 'SN').length
}))

const selectedItems = computed(() =>
  allItems.value.filter((item) => {
    if (item.kind === 'WO') return includeWork.value
    if (item.kind === 'OP') return includeOps.value
    return includeUnits.value
  })
)

const labels = computed<LabelItem[]>(() => {
  const list: LabelItem[] = []
  for (const item of selectedItems.value) {
    for (let i = 0; i < Math.max(1, Math.min(20, Number(copies.value) || 1)); i++) list.push(item)
  }
  return list
})

// 拼版时前面留空格，方便接着已用掉一半的标签纸继续打
const cells = computed<(LabelItem | null)[]>(() => {
  if (isSingle.value) return labels.value
  const blank = Math.max(0, Math.min(60, Number(offset.value) || 0))
  return [...Array.from({ length: blank }, () => null), ...labels.value]
})

const pageCount = computed(() => {
  const per = preset.value.cols * preset.value.rows
  if (isSingle.value) return labels.value.length
  return Math.max(1, Math.ceil(cells.value.length / per))
})

const sheetStyle = computed(() => {
  const p = preset.value
  return {
    '--cols': String(p.cols),
    '--lw': p.labelW + 'mm',
    '--lh': p.labelH + 'mm',
    '--qr': p.qr + 'mm',
    '--code-fs': p.codeFs + 'mm',
    '--name-fs': p.nameFs + 'mm',
    '--meta-fs': p.metaFs + 'mm',
    '--pad': p.pad + 'mm'
  } as Record<string, string>
})

let pageStyleEl: HTMLStyleElement | null = null

const applyPageRule = () => {
  const p = preset.value
  if (!pageStyleEl) {
    pageStyleEl = document.createElement('style')
    pageStyleEl.setAttribute('data-print-page', 'labels')
    document.head.appendChild(pageStyleEl)
  }
  pageStyleEl.textContent = `@page { size: ${p.pageW}mm ${p.pageH}mm; margin: 0; }`
}

const build = async () => {
  work.value = null
  allItems.value = []
  unitTotal.value = 0
  errorText.value = ''
  if (!workNo.value) {
    errorText.value = '缺少工单号参数（?workNo=xxx），请从生产工单页面打开标签打印。'
    return
  }
  loading.value = true
  try {
    const data = await api.get(`/work-orders/by-no/${encodeURIComponent(workNo.value)}`)
    work.value = data
    const items: LabelItem[] = []

    items.push({
      id: 'WO',
      kind: 'WO',
      tag: '工单码',
      main: data.workNo,
      name: data.productName,
      meta: `${data.productCode} · 计划 ${fmtQty(data.planQty)}${data.unit || ''}`,
      url: await QRCode.toDataURL(`MES|WO|${data.workNo}`, { width: 320, margin: 1, errorCorrectionLevel: 'M' })
    })

    for (const operation of data.operations || []) {
      items.push({
        id: `OP-${operation.seq}`,
        kind: 'OP',
        tag: `工序 ${operation.seq}`,
        main: data.workNo,
        name: `${operation.seq}. ${operation.name}`,
        meta: `${data.productCode} · 计划 ${fmtQty(operation.planQty)}${data.unit || ''}`,
        url: await QRCode.toDataURL(`MES|OP|${data.workNo}|${operation.seq}`, {
          width: 320,
          margin: 1,
          errorCorrectionLevel: 'M'
        })
      })
    }

    // 单件管理的产品，件码在工单下达时生成，这里按件打标签
    const unitData = await api.get('/work-order-units', { workOrderId: data.id }).catch(() => null)
    const units = unitData?.units || []
    unitTotal.value = units.length
    for (const unit of units.slice(0, UNIT_LABEL_LIMIT)) {
      items.push({
        id: `SN-${unit.unitNo}`,
        kind: 'SN',
        tag: `第 ${unit.seqNo} 件`,
        main: unit.unitNo,
        name: data.productName,
        meta: `${data.workNo} · ${data.productCode}`,
        url: await QRCode.toDataURL(`MES|SN|${unit.unitNo}`, { width: 320, margin: 1, errorCorrectionLevel: 'M' })
      })
    }
    allItems.value = items
    // 有件码默认只打件码，没有件码就打工单码 + 工序码
    if (unitTotal.value > 0) {
      includeWork.value = false
      includeOps.value = false
      includeUnits.value = true
    } else {
      includeWork.value = true
      includeOps.value = true
      includeUnits.value = false
    }
  } catch (err: any) {
    errorText.value =
      err?.response?.data?.message || err?.message || `加载工单 ${workNo.value} 失败，请稍后重试。`
  } finally {
    loading.value = false
  }
}

const printLabels = () => {
  if (!labels.value.length) return
  window.print()
}

const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/production')
}

watch(workNo, build)

onMounted(() => {
  applyPageRule()
  build()
})

onBeforeUnmount(() => {
  pageStyleEl?.remove()
  pageStyleEl = null
})
</script>

<template>
  <div class="print-page">
    <header class="toolbar no-print">
      <div class="toolbar-left">
        <button type="button" class="plain-btn" @click="goBack">返回</button>
        <div>
          <h1>标签打印</h1>
          <p v-if="work">
            {{ work.workNo }} · {{ work.productName }} · 工单码 1 张 / 工序码 {{ counts.OP }} 张 / 件码
            {{ unitTotal }} 张{{ unitTotal > UNIT_LABEL_LIMIT ? `（仅取前 ${UNIT_LABEL_LIMIT} 件）` : '' }}
          </p>
          <p v-else>贴到流转卡、工位看板与单件实物，车间扫码直接识别</p>
        </div>
      </div>
      <div class="toolbar-right">
        <label class="field">
          <span>标签纸</span>
          <select v-model="presetKey">
            <option v-for="p in PRESETS" :key="p.key" :value="p.key">{{ p.name }}</option>
          </select>
        </label>
        <label class="field">
          <span>份数</span>
          <input v-model.number="copies" type="number" min="1" max="20" />
        </label>
        <label v-if="!isSingle" class="field">
          <span>跳过前</span>
          <input v-model.number="offset" type="number" min="0" max="60" />
          <span>格</span>
        </label>
        <div class="checks">
          <label><input v-model="includeWork" type="checkbox" />工单码</label>
          <label><input v-model="includeOps" type="checkbox" />工序码</label>
          <label><input v-model="includeUnits" type="checkbox" :disabled="!unitTotal" />件码</label>
        </div>
        <button type="button" class="primary-btn" :disabled="!labels.length" @click="printLabels">打印</button>
      </div>
    </header>

    <div class="hints no-print">
      <span>共 {{ labels.length }} 张 · {{ pageCount }} 页 · 纸型 {{ preset.pageW }}×{{ preset.pageH }}mm</span>
      <span>打印设置：缩放 100%（标签边缘被裁就调到 98%）、边距「无」、关掉页眉页脚；二维码是图片，不用勾选「背景图形」</span>
    </div>

    <div v-if="!workNo || (errorText && !work)" class="empty">
      <p>{{ errorText || '缺少工单号参数' }}</p>
      <button type="button" class="plain-btn" @click="goBack">返回生产工单</button>
    </div>

    <div v-else-if="loading" class="empty">正在生成二维码…</div>

    <div v-else class="sheet" :class="{ single: isSingle }" :style="sheetStyle">
      <div v-for="(item, index) in cells" :key="index" class="cell" :class="{ blank: !item }">
        <div v-if="item" class="label">
          <div class="l-tag">{{ item.tag }}</div>
          <img :src="item.url" :alt="item.main" />
          <div class="l-code">{{ item.main }}</div>
          <div class="l-name">{{ item.name }}</div>
          <div class="l-meta">{{ item.meta }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.print-page {
  min-height: 100%;
  padding: 16px 18px 40px;
  background: #eef1f6;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e2e8f0;
}

.toolbar-left {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.toolbar h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.toolbar p {
  margin: 3px 0 0;
  font-size: 12px;
  color: #64748b;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.field {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #475569;
}

.field select,
.field input {
  height: 28px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 0 6px;
  font-size: 12px;
  background: #fff;
  color: #0f172a;
}

.field input {
  width: 54px;
}

.checks {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #475569;
}

.checks label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.plain-btn,
.primary-btn {
  height: 30px;
  padding: 0 14px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
}

.primary-btn {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
  font-weight: 600;
}

.primary-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.hints {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin: 10px 2px 14px;
  font-size: 11px;
  color: #64748b;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 60px 20px;
  border-radius: 10px;
  background: #fff;
  border: 1px dashed #cbd5e1;
  color: #64748b;
  font-size: 13px;
}

.sheet {
  display: grid;
  grid-template-columns: repeat(var(--cols), var(--lw));
  width: calc(var(--cols) * var(--lw));
  background: #fff;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.12);
  margin: 0 auto;
}

.sheet.single {
  width: var(--lw);
}

.cell {
  width: var(--lw);
  height: var(--lh);
  overflow: hidden;
  break-inside: avoid;
  page-break-inside: avoid;
}

.cell.blank {
  visibility: hidden;
}

.label {
  width: 100%;
  height: 100%;
  padding: var(--pad);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.4mm;
  text-align: center;
  border: 0.2mm dashed #cbd5e1;
  overflow: hidden;
}

.l-tag {
  font-size: var(--meta-fs);
  line-height: 1.1;
  color: #475569;
  letter-spacing: 0.2mm;
}

.label img {
  width: var(--qr);
  height: var(--qr);
  display: block;
  image-rendering: pixelated;
}

.l-code {
  font-size: var(--code-fs);
  font-weight: 700;
  line-height: 1.15;
  color: #0f172a;
  font-family: Bahnschrift, "Segoe UI", monospace;
  letter-spacing: 0.1mm;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.l-name {
  font-size: var(--name-fs);
  line-height: 1.15;
  color: #1e293b;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.l-meta {
  font-size: var(--meta-fs);
  line-height: 1.15;
  color: #64748b;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 单张热敏标签：空间紧，长件码允许折两行，第二行信息省略 */
.sheet.single .l-meta {
  display: none;
}

.sheet.single .l-code {
  white-space: normal;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.1;
}
</style>

<style>
@media print {
  html,
  body,
  #app {
    height: auto !important;
    background: #fff !important;
  }

  body {
    -webkit-print-color-adjust: exact !important;
    print-color-adjust: exact !important;
  }

  .no-print {
    display: none !important;
  }

  .print-page {
    min-height: 0 !important;
    padding: 0 !important;
    background: #fff !important;
  }

  .sheet {
    box-shadow: none !important;
    margin: 0 !important;
    width: calc(var(--cols) * var(--lw)) !important;
  }

  .cell {
    border: none !important;
  }

  .label {
    border: none !important;
  }

  /* 单张标签纸：一张一页 */
  .sheet.single .cell {
    page-break-after: always;
    break-after: page;
  }

  .sheet.single .cell:last-child {
    page-break-after: auto;
    break-after: auto;
  }
}
</style>
