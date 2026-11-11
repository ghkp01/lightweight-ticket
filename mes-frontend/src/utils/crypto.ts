import { api } from '../api'

const PEM_PATTERN = /-----[^-]+-----/g

const pemToArrayBuffer = (pem: string) => {
  const base64 = pem.replace(PEM_PATTERN, '').replace(/\s+/g, '')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes.buffer
}

const toBase64 = (buffer: ArrayBuffer) => {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary)
}

export interface EncryptedSecret {
  encryptedPassword: string
  keyId: string
}

const fetchKey = async (): Promise<{ key: CryptoKey; keyId: string } | null> => {
  if (!window.crypto?.subtle) {
    return null
  }
  const { publicKey, keyId } = await api.get('/auth/public-key')
  const key = await window.crypto.subtle.importKey(
    'spki',
    pemToArrayBuffer(publicKey),
    { name: 'RSA-OAEP', hash: 'SHA-256' },
    false,
    ['encrypt']
  )
  return { key, keyId }
}

const encryptWith = async (key: CryptoKey, plain: string) => {
  const cipher = await window.crypto.subtle.encrypt(
    { name: 'RSA-OAEP' },
    key,
    new TextEncoder().encode(plain)
  )
  return toBase64(cipher)
}

/**
 * 密码传输加密：拉后端公钥，用 RSA-OAEP(SHA-256) 加密后提交。
 * 非安全上下文（例如局域网 http 直连）浏览器不提供 crypto.subtle，
 * 此时回退明文，由后端兼容处理；正式环境请走 https。
 */
export const encryptPassword = async (
  plain: string
): Promise<EncryptedSecret | { password: string }> => {
  const pair = await fetchKey()
  if (!pair) {
    return { password: plain }
  }
  return { encryptedPassword: await encryptWith(pair.key, plain), keyId: pair.keyId }
}

/** 修改密码需要同时加密原密码与新密码，复用同一把公钥 */
export const encryptPasswordPair = async (oldPlain: string, newPlain: string) => {
  const pair = await fetchKey()
  if (!pair) {
    return { oldPassword: oldPlain, newPassword: newPlain }
  }
  return {
    encryptedOldPassword: await encryptWith(pair.key, oldPlain),
    encryptedNewPassword: await encryptWith(pair.key, newPlain),
    keyId: pair.keyId
  }
}
