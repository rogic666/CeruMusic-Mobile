import type { LyricLine } from '../playback/player'

/**
 * Minimal LRC reader/writer used for the `.lrc` files saved next to downloads.
 *
 * Only `[mm:ss.xx]` lines are produced, which every desktop and mobile player
 * understands, so the sidecar files stay useful outside Ceru.
 */

const TIME_TAG = /^\[(\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?\]/

function toMillis(minutes: string, seconds: string, fraction?: string): number {
  const pad = (fraction ?? '').padEnd(3, '0').slice(0, 3)
  return Number(minutes) * 60_000 + Number(seconds) * 1_000 + (pad ? Number(pad) : 0)
}

/** Parses LRC text; a repeated timestamp is treated as the translation line. */
export function parseLrcText(raw: string): LyricLine[] {
  const rows: { time: number; text: string }[] = []
  for (const source of raw.split(/\r?\n/)) {
    let rest = source.trim()
    if (!rest) continue
    const times: number[] = []
    for (;;) {
      const match = TIME_TAG.exec(rest)
      if (!match) break
      times.push(toMillis(match[1], match[2], match[3]))
      rest = rest.slice(match[0].length)
    }
    if (!times.length) continue
    const text = rest.trim()
    for (const time of times) rows.push({ time, text })
  }
  if (!rows.length) return []
  rows.sort((left, right) => left.time - right.time)

  const lines: LyricLine[] = []
  for (const row of rows) {
    const previous = lines[lines.length - 1]
    if (previous && Math.abs(previous.startTimeMs - row.time) < 40 && row.text) {
      if (!previous.text) {
        previous.text = row.text
        continue
      }
      if (!previous.translation) {
        previous.translation = row.text
        continue
      }
    }
    lines.push({ startTimeMs: row.time, text: row.text })
  }

  const timed = lines.filter((line) => line.text || line.translation)
  return timed.map((line, index) => {
    const next = timed[index + 1]
    const endTimeMs =
      next && next.startTimeMs > line.startTimeMs ? next.startTimeMs : line.startTimeMs + 5_000
    return { ...line, endTimeMs }
  })
}

function stamp(milliseconds: number): string {
  const total = Math.max(0, Math.round(milliseconds))
  const minutes = Math.floor(total / 60_000)
  const seconds = Math.floor((total % 60_000) / 1_000)
  const centiseconds = Math.floor((total % 1_000) / 10)
  return `[${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(
    centiseconds
  ).padStart(2, '0')}]`
}

export function linesToLrc(
  lines: LyricLine[],
  meta: { title?: string; artist?: string; album?: string; offsetMs?: number } = {}
): string {
  const output: string[] = []
  if (meta.title) output.push(`[ti:${meta.title}]`)
  if (meta.artist) output.push(`[ar:${meta.artist}]`)
  if (meta.album) output.push(`[al:${meta.album}]`)
  output.push('[by:Ceru Music]')
  if (meta.offsetMs) output.push(`[offset:${Math.round(meta.offsetMs)}]`)
  for (const line of lines) {
    if (line.text) output.push(`${stamp(line.startTimeMs)}${line.text}`)
    if (line.translation) output.push(`${stamp(line.startTimeMs)}${line.translation}`)
  }
  return `${output.join('\n')}\n`
}
