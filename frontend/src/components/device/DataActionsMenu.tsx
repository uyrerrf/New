import { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { MoreVertical, FileJson, FileSpreadsheet, Trash2, Archive, Loader2 } from 'lucide-react';
import type { ZipFileEntry } from '@/lib/export';

export interface DataActionItem {
  label: string;
  icon: React.ElementType;
  onClick: () => void;
  variant?: 'default' | 'destructive';
  disabled?: boolean;
}

interface DataActionsMenuProps {
  actions: DataActionItem[];
  disabled?: boolean;

  loadingLabel?: string | null;
}

export function DataActionsMenu({ actions, disabled = false, loadingLabel = null }: DataActionsMenuProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        close();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, close]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, close]);

  if (actions.length === 0) return null;

  return (
    <div ref={containerRef} className="relative">
      <Button
        variant="outline"
        size="sm"
        className="px-2"
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        aria-label="More actions"
        aria-expanded={open}
      >
        <MoreVertical className="h-3.5 w-3.5" />
      </Button>
      {open && (
        <div
          className="absolute left-0 top-full mt-1 z-50 min-w-[180px] rounded-lg border bg-popover p-1 shadow-md animate-in fade-in-0 zoom-in-95"
          role="menu"
        >
          {actions.map((action, i) => {
            const Icon = action.icon;
            const isLoading = loadingLabel === action.label;
            return (
              <button
                key={action.label + i}
                role="menuitem"
                disabled={action.disabled || isLoading}
                className={`flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors ${
                  action.variant === 'destructive'
                    ? 'text-destructive hover:bg-destructive/10 focus:bg-destructive/10'
                    : 'text-foreground hover:bg-muted focus:bg-muted'
                } ${action.disabled || isLoading ? 'opacity-50 pointer-events-none' : ''}`}
                onClick={() => {
                  action.onClick();
                  close();
                }}
              >
                {isLoading ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Icon className="h-3.5 w-3.5" />
                )}
                {isLoading ? `${action.label}...` : action.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export function buildDataActions(opts: {
  data: unknown[] | null | undefined;
  exportPrefix: string;
  onClear: () => void;
  extraActions?: DataActionItem[];
}): DataActionItem[] {
  const { data, exportPrefix, onClear, extraActions } = opts;
  const hasData = Array.isArray(data) && data.length > 0;
  const actions: DataActionItem[] = [];

  if (extraActions) {
    actions.push(...extraActions);
  }

  actions.push(
    {
      label: 'Export CSV',
      icon: FileSpreadsheet,
      onClick: () => {
        if (!hasData) return;
        import('@/lib/export').then(({ exportCSV, timestampedFilename }) => {
          exportCSV(data as Record<string, unknown>[], timestampedFilename(exportPrefix));
        });
      },
      disabled: !hasData,
    },
    {
      label: 'Export JSON',
      icon: FileJson,
      onClick: () => {
        if (!hasData) return;
        import('@/lib/export').then(({ exportJSON, timestampedFilename }) => {
          exportJSON(data, timestampedFilename(exportPrefix));
        });
      },
      disabled: !hasData,
    },
    {
      label: 'Clear Data',
      icon: Trash2,
      onClick: onClear,
      variant: 'destructive',
      disabled: !hasData,
    }
  );

  return actions;
}

export function buildFileActions(opts: {
  files: ZipFileEntry[];

  metadata: unknown[] | null | undefined;
  exportPrefix: string;
  onClear: () => void;
  onExportStart?: () => void;
  onExportEnd?: () => void;
  extraActions?: DataActionItem[];
}): DataActionItem[] {
  const { files, metadata, exportPrefix, onClear, onExportStart, onExportEnd, extraActions } = opts;
  const hasFiles = files.length > 0;
  const hasMetadata = Array.isArray(metadata) && metadata.length > 0;
  const actions: DataActionItem[] = [];

  if (extraActions) {
    actions.push(...extraActions);
  }

  actions.push(
    {
      label: 'Export ZIP',
      icon: Archive,
      onClick: () => {
        if (!hasFiles) return;
        onExportStart?.();
        import('@/lib/export').then(({ exportZIP, timestampedFilename }) => {
          exportZIP(files, timestampedFilename(exportPrefix)).finally(() => {
            onExportEnd?.();
          });
        });
      },
      disabled: !hasFiles,
    },
    {
      label: 'Export JSON',
      icon: FileJson,
      onClick: () => {
        if (!hasMetadata) return;
        import('@/lib/export').then(({ exportJSON, timestampedFilename }) => {
          exportJSON(metadata, timestampedFilename(`${exportPrefix}-metadata`));
        });
      },
      disabled: !hasMetadata,
    },
    {
      label: 'Clear Data',
      icon: Trash2,
      onClick: onClear,
      variant: 'destructive',
      disabled: !hasFiles && !hasMetadata,
    }
  );

  return actions;
}
