/**
 * 条码解析：与 PC 端打印的标签保持一致
 * - MES|WO|工单号
 * - MES|OP|工单号|工序序号
 * - MES|MAT|物料编码
 * 纯文本条码（扫码枪直接敲出来的）只支持工单号，其余要求带 MES 前缀。
 */
export const parseScanCode = (raw) => {
  const text = String(raw || '').trim()
  if (!text) return { invalid: '没有识别到内容' }

  const parts = text.split('|').map((part) => part.trim())
  if (parts.length >= 3 && parts[0].toUpperCase() === 'MES') {
    const kind = parts[1].toUpperCase()
    if (kind === 'WO' && parts[2]) {
      return { kind: 'WO', workNo: parts[2], opSeq: null, raw: text }
    }
    if (kind === 'OP' && parts[2]) {
      const seq = Number(parts[3])
      if (!parts[3] || Number.isNaN(seq)) return { invalid: '工序码缺少工序序号' }
      return { kind: 'OP', workNo: parts[2], opSeq: seq, raw: text }
    }
    if (kind === 'MAT' && parts[2]) {
      return { kind: 'MAT', code: parts[2], raw: text }
    }
    return { invalid: '未知的条码类型' }
  }

  if (/^WO/i.test(text)) {
    return { kind: 'WO', workNo: text.toUpperCase(), opSeq: null, raw: text }
  }

  return { invalid: '无法识别的编码' }
}

/** 扫出来的东西统一转成检验页能吃的参数 */
export const toInspectionQuery = (parsed) => {
  if (!parsed || parsed.invalid) return ''
  const params = []
  if (parsed.kind === 'OP') {
    params.push(`workNo=${encodeURIComponent(parsed.workNo)}`)
    params.push(`opSeq=${parsed.opSeq}`)
  } else if (parsed.kind === 'WO') {
    params.push(`workNo=${encodeURIComponent(parsed.workNo)}`)
  } else {
    return ''
  }
  return params.join('&')
}
