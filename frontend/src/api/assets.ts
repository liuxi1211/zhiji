import request from './request'

/**
 * 图片本地化 / 上传（B 端员工会话，/api/admin/assets）。
 *
 * 本地化把正文里的外链图收进公共图片库并换本系统地址，**不落库**、返回明细报告，
 * 交人工在附件面板查看与补救；提交进标准库时的自动本地化是兜底。
 */

/** 单条外链本地化结果：原文地址 + 结果（成功给本地名，失败给原因） */
export interface ImageLocalizeResult {
  url: string
  ok: boolean
  localName: string | null
  reason: string
}

/** 本地化报告：改写后正文 + 逐条明细 + 成功/失败计数 */
export interface LocalizeReport {
  contentMd: string
  images: ImageLocalizeResult[]
  success: number
  failed: number
}

/** 本地化正文里的外链图片（不落库，返回报告；改写结果由人工确认后回编辑器） */
export async function localizeImages(contentMd: string): Promise<LocalizeReport> {
  return (
    await request.post<LocalizeReport>(
      '/admin/assets/localize',
      { contentMd },
      // 外链图逐张下载，长文可能较慢
      { timeout: 180000 }
    )
  ).data
}

/** 人工上传替换：把（人工下载到的）图片收进公共图片库，返回可引用的公开地址 */
export async function uploadImage(file: File): Promise<{ url: string; name: string }> {
  const form = new FormData()
  form.append('file', file)
  return (
    await request.post<{ url: string; name: string }>('/admin/assets/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
  ).data
}
