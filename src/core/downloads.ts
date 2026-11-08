import { reactive } from 'vue'
import { Capacitor } from '@capacitor/core'
import { resolveTrackSource } from './playback/source'
import { settingsState } from './settings'
import { qualityLabel } from './quality'
import { linesToLrc } from './lyrics/local'
import type { Track } from './track'
import type { LyricLine } from './playback/player'
import { Downloader, type DownloadProgressEvent } from './native/plugins'

type DownloadStatus = 'queued' | 'downloading' | 'completed' | 'failed' | 'cancelled'

export interface DownloadTask {
  id: string
  track: Track
  quality: string
  status: DownloadStatus
  progress: number
  received: number
  total: number
  fileName: string
  /** Absolute path in the app-private folder (empty for SAF downloads). */
  path: string
  /** SAF document URI when the file lives in a user-picked folder. */
  uri: string
  /** SAF tree URI used for the download; empty for the app-private folder. */
  treeUri: string
  /** Sidecar file names saved next to the audio file. */
  lyricsFile: string
  coverFile: string
  size: number
  /** True once the cover/lyrics were embedded into the audio file. */
  tagged?: boolean
  error: string
  createdAt: number
  blobUrl?: string
}

const STORAGE_KEY = 'ceru.downloads.v1'
const DEFAULT_CONCURRENT = 2
const AUDIO_EXTENSIONS = ['mp3', 'flac', 'm4a', 'aac', 'ogg', 'opus', 'wav', 'wma']

export const downloadState = reactive({
  tasks: [] as DownloadTask[],
  ready: false,
  active: 0
})

let listenerReady = false
const pending: string[] = []
let activeCount = 0

function persist(): void {
  try {
    const data = downloadState.tasks.map(({ blobUrl, ...task }) => task)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch {}
}

function findTask(id: string): DownloadTask | undefined {
  return downloadState.tasks.find((task) => task.id === id)
}

function taskFor(track: Track): DownloadTask | undefined {
  return downloadState.tasks.find(
    (task) => task.track.id === track.id && task.track.providerId === track.providerId
  )
}

function completedTaskFor(track: Track): DownloadTask | undefined {
  const task = taskFor(track)
  return task?.status === 'completed' ? task : undefined
}

export function isDownloaded(track: Track): boolean {
  return Boolean(completedTaskFor(track))
}

export function isDownloading(track: Track): boolean {
  const task = taskFor(track)
  return task?.status === 'queued' || task?.status === 'downloading'
}

/** Local file URL for offline playback, empty when the track is not downloaded. */
export function localUrlFor(track: Track): string {
  const task = completedTaskFor(track)
  if (!task) return ''
  if (task.blobUrl) return task.blobUrl
  if (!task.path) return ''
  return Capacitor.convertFileSrc(task.path)
}

/** Folder shown in settings and the downloads page. */
export function downloadFolderLabel(): string {
  if (!settingsState.downloadDirectoryUri) return '应用专属目录'
  return settingsState.downloadDirectoryLabel || '已选目录'
}

/** Opens the system folder picker; `uri` is empty when the user cancels. */
export async function chooseDownloadFolder(): Promise<{ uri: string; label: string }> {
  if (!Capacitor.isNativePlatform()) return { uri: '', label: '' }
  const result = await Downloader.chooseDirectory()
  return { uri: result.uri, label: result.label }
}

/** Forgets the custom folder; files already downloaded stay where they are. */
export async function resetDownloadFolder(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return
  await Downloader.clearDirectory()
}

/**
 * Playable URL for a downloaded track. SAF documents cannot be loaded by the
 * audio element directly, so they are proxied through the native loopback
 * server; app-private files keep using the cheap convertFileSrc path.
 */
export async function localPlaybackUrl(track: Track): Promise<string> {
  const task = completedTaskFor(track)
  if (!task) return ''
  if (task.blobUrl) return task.blobUrl
  if (task.treeUri && task.fileName && Capacitor.isNativePlatform()) {
    try {
      const { url } = await Downloader.localUrl({ fileName: task.fileName, treeUri: task.treeUri })
      return url
    } catch {
      return ''
    }
  }
  return localUrlFor(track)
}

/** Text of the `.lrc` saved next to the download, empty when there is none. */
export async function localLyricsText(track: Track): Promise<string> {
  const task = completedTaskFor(track)
  if (!task?.lyricsFile || !Capacitor.isNativePlatform()) return ''
  try {
    const { text } = await Downloader.readText({
      fileName: task.lyricsFile,
      treeUri: task.treeUri || ''
    })
    return text
  } catch {
    return ''
  }
}

type LyricFetcher = (track: Track) => Promise<LyricLine[]>

let lyricFetcher: LyricFetcher | null = null

/**
 * main.ts injects the player's lyric fetcher here; downloads never imports the
 * player module so the two stay free of an import cycle.
 */
export function registerSidecarSources(sources: { lyrics?: LyricFetcher }): void {
  lyricFetcher = sources.lyrics ?? null
}

function sidecarBase(fileName: string): string {
  return fileName.replace(/\.[^.\\/]+$/, '')
}

function coverUrlFor(track: Track): string {
  const url = track.artwork || ''
  return /^https?:\/\//i.test(url) ? url : ''
}

/** Writes the matching .lrc / cover files next to a finished download. */
async function writeSidecars(task: DownloadTask): Promise<void> {
  if (!task.fileName || !Capacitor.isNativePlatform()) return
  const base = sidecarBase(task.fileName)
  const treeUri = task.treeUri || ''
  let lyricsText = ''
  if (settingsState.downloadLyrics && lyricFetcher) {
    try {
      const lines = await lyricFetcher(task.track)
      if (lines.length) {
        const text = linesToLrc(lines, {
          title: task.track.title,
          artist: task.track.artist,
          album: task.track.album
        })
        lyricsText = text
        const written = await Downloader.writeText({ fileName: `${base}.lrc`, text, treeUri })
        task.lyricsFile = written.fileName
        persist()
      }
    } catch {}
  }
  if (settingsState.downloadCover) {
    const url = coverUrlFor(task.track)
    if (url) {
      try {
        const result = await Downloader.download({
          id: `${task.id}-cover`,
          url,
          fileName: `${base}.jpg`,
          treeUri,
          overwrite: true
        })
        task.coverFile = result.fileName
        persist()
      } catch {}
    }
  }
  await embedTags(task, lyricsText)
}

/**
 * Players such as Salt Player ignore sidecar images, so the cover and lyrics are
 * also written into the audio file itself. Failures are ignored: the downloaded
 * song and the sidecar files are still usable.
 */
async function embedTags(task: DownloadTask, lyrics: string): Promise<void> {
  try {
    const result = await Downloader.embedTags({
      fileName: task.fileName,
      treeUri: task.treeUri || '',
      coverName: settingsState.downloadCover ? task.coverFile : '',
      title: task.track.title ?? '',
      artist: task.track.artist ?? '',
      album: task.track.album ?? '',
      lyrics
    })
    task.tagged = Boolean(result?.tagged)
    persist()
  } catch {}
}

function sanitizeFileName(value: string): string {
  return (
    value
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
      .replace(/\s+/g, ' ')
      .replace(/^[.\s]+|[.\s]+$/g, '')
      .slice(0, 100) || 'audio'
  )
}

function extensionFromUrl(url: string): string {
  try {
    const path = new URL(url).pathname
    const match = path.match(/\.([A-Za-z0-9]{2,4})$/)
    const extension = match?.[1]?.toLowerCase()
    if (extension && AUDIO_EXTENSIONS.includes(extension)) return extension
  } catch {}
  return ''
}

/** Expands the desktop-style %t/%s/%a/%u/%q/%d filename template. */
export function formatDownloadTemplate(template: string, track: Track, quality: string): string {
  const date = new Date()
  const day = [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0')
  ].join('-')
  const values: Record<string, string> = {
    '%t': track.title || '未知歌曲',
    '%s': track.artist || '未知歌手',
    '%a': track.album || '',
    '%u': track.providerName || track.providerId || '',
    '%q': qualityLabel(quality),
    '%d': day
  }
  let name = template || '%t - %s'
  for (const [token, value] of Object.entries(values)) {
    name = name.split(token).join(value)
  }
  name = name.replace(/\s+/g, ' ').replace(/^[\s\-_.]+|[\s\-_.]+$/g, '')
  if (!name) name = `${track.artist || '未知歌手'} - ${track.title || '未知歌曲'}`
  return name
}

function buildFileName(track: Track, url: string, quality: string): string {
  const extension =
    extensionFromUrl(url) ||
    (quality.includes('flac') || quality === 'lossless' ? 'flac' : 'mp3')
  const base = sanitizeFileName(
    formatDownloadTemplate(settingsState.downloadFilenameTemplate, track, quality)
  )
  return `${base}.${extension}`
}

export async function initDownloads(): Promise<void> {
  if (downloadState.ready) return
  downloadState.ready = true
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const saved = JSON.parse(raw)
      if (Array.isArray(saved)) {
        downloadState.tasks = saved.map((task: DownloadTask) => ({
          ...task,
          uri: task.uri ?? '',
          treeUri: task.treeUri ?? '',
          lyricsFile: task.lyricsFile ?? '',
          coverFile: task.coverFile ?? '',
          progress: task.status === 'completed' ? 1 : 0,
          received: task.received ?? 0,
          total: task.total ?? 0
        }))
      }
    }
  } catch {
    downloadState.tasks = []
  }
  for (const task of downloadState.tasks) {
    if (task.status === 'downloading' || task.status === 'queued') {
      task.status = 'failed'
      task.error = '上次下载被中断'
    }
  }
  if (Capacitor.isNativePlatform() && !listenerReady) {
    listenerReady = true
    void Downloader.addListener('progress', (event: DownloadProgressEvent) => {
      const task = findTask(event.id)
      if (!task || task.status !== 'downloading') return
      task.received = event.received
      task.total = event.total
      task.progress =
        event.total > 0 ? Math.min(0.99, event.received / event.total) : 0
    })
  }
  persist()
  void verifyFiles()
}

async function verifyFiles(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return
  for (const task of downloadState.tasks) {
    if (task.status !== 'completed' || !task.fileName) continue
    try {
      const probe = await Downloader.exists({
        fileName: task.fileName,
        treeUri: task.treeUri || ''
      })
      if (!probe.exists) throw new Error('missing')
    } catch {
      task.status = 'failed'
      task.error = '文件已被删除'
      task.path = ''
      task.uri = ''
      task.progress = 0
    }
  }
  persist()
}

function enqueue(id: string): void {
  if (!pending.includes(id)) pending.push(id)
  pump()
}

function concurrencyLimit(): number {
  const value = Number(settingsState.downloadConcurrency)
  return Math.max(1, Math.min(3, Number.isFinite(value) && value > 0 ? value : DEFAULT_CONCURRENT))
}

function pump(): void {
  const limit = concurrencyLimit()
  while (activeCount < limit) {
    const id = pending.shift()
    if (!id) break
    const task = findTask(id)
    if (!task || task.status !== 'queued') continue
    activeCount += 1
    downloadState.active = activeCount
    void runTask(task)
      .finally(() => {
        activeCount -= 1
        downloadState.active = activeCount
        pump()
      })
  }
}

async function runTask(task: DownloadTask): Promise<void> {
  task.status = 'downloading'
  task.error = ''
  task.progress = 0
  task.received = 0
  task.total = 0
  persist()
  try {
    const source = await resolveTrackSource(task.track, task.quality)
    if (!Capacitor.isNativePlatform()) {
      const response = await fetch(source.url, { headers: source.headers })
      if (!response.ok) throw new Error(`下载失败: HTTP ${response.status}`)
      const blob = await response.blob()
      task.blobUrl = URL.createObjectURL(blob)
      task.status = 'completed'
      task.progress = 1
      task.size = blob.size
      task.received = blob.size
      task.total = blob.size
      return
    }
    const treeUri = settingsState.downloadDirectoryUri || ''
    const fileName = buildFileName(task.track, source.url, task.quality)
    task.fileName = fileName
    const result = await Downloader.download({
      id: task.id,
      url: source.url,
      fileName,
      headers: source.headers,
      treeUri
    })
    task.fileName = result.fileName
    task.path = result.path
    task.uri = result.uri ?? ''
    task.treeUri = treeUri
    task.size = result.size
    task.received = result.size
    task.total = result.size
    task.progress = 1
    task.status = 'completed'
    void writeSidecars(task)
  } catch (error: any) {
    if ((task.status as DownloadStatus) === 'cancelled') return
    task.status = 'failed'
    task.error = error?.message ?? '下载失败'
  } finally {
    persist()
  }
}

export function downloadTrack(
  track: Track,
  quality = settingsState.downloadQuality || settingsState.quality
): DownloadTask {
  const existing = taskFor(track)
  if (existing && existing.status === 'completed') return existing
  if (existing && (existing.status === 'downloading' || existing.status === 'queued')) {
    return existing
  }
  if (existing) {
    existing.status = 'queued'
    existing.quality = quality
    existing.error = ''
    existing.progress = 0
    existing.received = 0
    existing.total = 0
    persist()
    enqueue(existing.id)
    return existing
  }
  const task: DownloadTask = {
    id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    track,
    quality,
    status: 'queued',
    progress: 0,
    received: 0,
    total: 0,
    fileName: '',
    path: '',
    uri: '',
    treeUri: '',
    lyricsFile: '',
    coverFile: '',
    size: 0,
    error: '',
    createdAt: Date.now()
  }
  downloadState.tasks.unshift(task)
  persist()
  enqueue(task.id)
  return task
}

export function downloadTracks(
  tracks: Track[],
  quality = settingsState.downloadQuality || settingsState.quality
): number {
  let started = 0
  for (const track of tracks) {
    const task = taskFor(track)
    if (task?.status === 'completed') continue
    downloadTrack(track, quality)
    started += 1
  }
  return started
}

export async function cancelTask(id: string): Promise<void> {
  const task = findTask(id)
  if (!task) return
  if (task.status === 'completed') return
  task.status = 'cancelled'
  task.error = '已取消'
  const index = pending.indexOf(id)
  if (index >= 0) pending.splice(index, 1)
  if (Capacitor.isNativePlatform()) {
    try {
      await Downloader.cancel({ id })
    } catch {}
  }
  persist()
}

export function retryTask(id: string): void {
  const task = findTask(id)
  if (!task) return
  task.status = 'queued'
  task.error = ''
  task.progress = 0
  task.received = 0
  task.total = 0
  persist()
  enqueue(id)
}

export async function removeTask(id: string, deleteFile = true): Promise<void> {
  const index = downloadState.tasks.findIndex((task) => task.id === id)
  if (index < 0) return
  const [task] = downloadState.tasks.splice(index, 1)
  if (task.blobUrl) URL.revokeObjectURL(task.blobUrl)
  if (deleteFile && Capacitor.isNativePlatform()) {
    for (const name of [task.fileName, task.lyricsFile, task.coverFile]) {
      if (!name) continue
      try {
        await Downloader.delete({ fileName: name, treeUri: task.treeUri || '' })
      } catch {}
    }
  }
  persist()
}

export async function clearCompleted(): Promise<void> {
  const completed = downloadState.tasks.filter((task) => task.status === 'completed')
  for (const task of completed) await removeTask(task.id, true)
}

export function downloadSummary(): { total: number; completed: number; active: number; failed: number } {
  const tasks = downloadState.tasks
  return {
    total: tasks.length,
    completed: tasks.filter((task) => task.status === 'completed').length,
    active: tasks.filter((task) => task.status === 'downloading' || task.status === 'queued').length,
    failed: tasks.filter((task) => task.status === 'failed').length
  }
}
