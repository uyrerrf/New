import JSZip from 'jszip';
import { fetchAuthBlob } from '@/services/api';

function csvEscape(value: unknown): string {
  const s = value == null ? '' : String(value);
  if (s.includes('"') || s.includes(',') || s.includes('\n') || s.includes('\r')) {
    return `"${s.replace(/"/g, '""')}"`;
  }
  return s;
}

export function toCSV<T extends Record<string, unknown>>(rows: T[], columns?: (keyof T)[]): string {
  if (rows.length === 0) return '';

  const cols = columns ?? (Object.keys(rows[0]) as (keyof T)[]);
  const header = cols.map((c) => csvEscape(c as string)).join(',');
  const body = rows.map((row) =>
    cols.map((col) => {
      const val = row[col];
      if (val == null) return '';
      if (Array.isArray(val)) return csvEscape(val.join('; '));
      if (typeof val === 'object') return csvEscape(JSON.stringify(val));
      return csvEscape(val);
    }).join(',')
  );
  return [header, ...body].join('\n');
}

export function toJSON<T>(data: T): string {
  return JSON.stringify(data, null, 2);
}

export function downloadFile(content: string | Blob, filename: string, mimeType: string): void {
  const blob = content instanceof Blob ? content : new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);

  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function exportCSV<T extends Record<string, unknown>>(
  rows: T[],
  filename: string,
  columns?: (keyof T)[]
): void {
  if (rows.length === 0) return;
  const csv = toCSV(rows, columns);
  downloadFile(csv, filename.endsWith('.csv') ? filename : `${filename}.csv`, 'text/csv;charset=utf-8;');
}

export function exportJSON<T>(data: T, filename: string): void {
  const json = toJSON(data);
  downloadFile(json, filename.endsWith('.json') ? filename : `${filename}.json`, 'application/json');
}

export function timestampedFilename(prefix: string): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const ts = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(now.getSeconds())}`;
  return `${prefix}_${ts}`;
}

export interface ZipFileEntry {
  url: string;

  name: string;
}

export async function exportZIP(
  entries: ZipFileEntry[],
  zipFilename: string,
  onProgress?: (completed: number, total: number) => void
): Promise<void> {
  if (entries.length === 0) return;

  const zip = new JSZip();
  const total = entries.length;
  let completed = 0;
  let succeeded = 0;
  const failedIndices = new Set<number>();

  const CONCURRENCY = 5;
  const batches: ZipFileEntry[][] = [];
  for (let i = 0; i < entries.length; i += CONCURRENCY) {
    batches.push(entries.slice(i, i + CONCURRENCY));
  }

  let entryIndex = 0;
  for (const batch of batches) {
    const batchStartIndex = entryIndex;
    const results = await Promise.allSettled(
      batch.map(async (entry) => {
        const blob = await fetchAuthBlob(entry.url);
        return { entry, blob };
      })
    );

    results.forEach((result, j) => {
      const originalIndex = batchStartIndex + j;
      if (result.status === 'fulfilled' && result.value.blob) {
        let name = result.value.entry.name;
        const existing = zip.file(name);
        if (existing) {
          const dotIdx = name.lastIndexOf('.');
          const base = dotIdx > 0 ? name.substring(0, dotIdx) : name;
          const ext = dotIdx > 0 ? name.substring(dotIdx) : '';
          let counter = 1;
          while (zip.file(`${base}_${counter}${ext}`)) counter++;
          name = `${base}_${counter}${ext}`;
        }
        zip.file(name, result.value.blob);
        succeeded++;
      } else {
        failedIndices.add(originalIndex);
      }
      completed++;
      onProgress?.(completed, total);
    });
    entryIndex += batch.length;
  }

  if (succeeded < total) {
    const manifest = entries.map((e, i) => {
      const inZip = failedIndices.has(i) ? 'failed' : 'ok';
      return `${e.name},${e.url},${inZip}`;
    });
    zip.file('_manifest.csv', 'name,url,status\n' + manifest.join('\n'));
  }

  const blob = await zip.generateAsync({ type: 'blob' });
  const filename = zipFilename.endsWith('.zip') ? zipFilename : `${zipFilename}.zip`;
  downloadFile(blob, filename, 'application/zip');
}
