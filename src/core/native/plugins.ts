import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export interface DownloadProgressEvent {
  id: string
  received: number
  /** -1 when the server does not send Content-Length. */
  total: number
}

interface DownloadResult {
  id: string
  path: string
  /** SAF document URI when the download went to a user-picked folder. */
  uri?: string
  fileName: string
  size: number
}

export interface DownloadDirectory {
  /** Empty when downloads use the app-private folder. */
  uri: string
  label: string
  isDefault: boolean
}

export interface DownloaderPlugin {
  download(options: {
    id: string
    url: string
    fileName: string
    headers?: Record<string, string>
    /** SAF tree URI; omit to write into the app-private Music folder. */
    treeUri?: string
    /** Replace an existing file instead of adding a numbered copy. */
    overwrite?: boolean
  }): Promise<DownloadResult>
  cancel(options: { id: string }): Promise<void>
  delete(options: { fileName: string; treeUri?: string }): Promise<void>
  exists(options: { fileName: string; treeUri?: string }): Promise<{ exists: boolean; size: number }>
  readText(options: { fileName: string; treeUri?: string }): Promise<{ text: string }>
  writeText(options: {
    fileName: string
    text: string
    treeUri?: string
  }): Promise<{ fileName: string; path: string; uri: string; size: number }>
  /** Loopback URL for files the WebView cannot read directly (content://). */
  localUrl(options: { fileName: string; treeUri?: string }): Promise<{ url: string; size: number }>
  chooseDirectory(): Promise<DownloadDirectory>
  getDirectory(): Promise<DownloadDirectory>
  clearDirectory(): Promise<DownloadDirectory>
  addListener(
    event: 'progress',
    listener: (event: DownloadProgressEvent) => void
  ): Promise<PluginListenerHandle>
}

export interface ExternalPlugin {
  open(options: { url: string }): Promise<void>
  installApk(options: { fileName: string }): Promise<{ needsPermission?: boolean; started?: boolean }>
  notifyUpdate(options: { title: string; body: string }): Promise<void>
}

export const Downloader = registerPlugin<DownloaderPlugin>('Downloader')
export const External = registerPlugin<ExternalPlugin>('External')
