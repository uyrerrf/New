import { useState, useRef, useEffect, useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, FileEntry } from '@/types';
import { CMD, normalizeFileList, extractList } from '@/types';
import { DevicePageHeader, ErrorAlert, EmptyState, StatusBadge, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu } from '@/components/device/DataActionsMenu';
import type { DataActionItem } from '@/components/device/DataActionsMenu';
import { filesApi } from '@/services/api';
import { FileJson } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  FolderOpen, Download, Upload, Trash2, Pencil, Lock, Unlock, ArrowLeft, ArrowRight,
  MoreVertical, CheckSquare, X, HardDrive,
  File, Folder, FolderSymlink, ChevronRight,
  FileText, FileImage, FileVideo, FileAudio, FileArchive,
  FileCode, Loader2
} from 'lucide-react';
import { formatBytes, cn } from '@/lib/utils';
import { onTransferUpdate, onCommandStatus } from '@/services/socket';
import { useConfirm, PasswordDialog, PromptDialog } from '@/components/ConfirmDialog';

function getFileExt(name: string): string {
  const idx = name.lastIndexOf('.');
  return idx > 0 ? name.substring(idx + 1).toLowerCase() : '';
}

function FileIcon({ file }: { file: FileEntry }) {
  if (file.isDir) {
    if (file.name === '../') return <FolderSymlink className="h-4 w-4 text-primary shrink-0" />;
    return <Folder className="h-4 w-4 text-primary shrink-0" />;
  }
  const ext = getFileExt(file.name);
  const c = "h-4 w-4 shrink-0";
  switch (ext) {
    case 'jpg': case 'jpeg': case 'png': case 'gif': case 'bmp': case 'webp': case 'svg':
      return <FileImage className={`${c} text-pink-500`} />;
    case 'mp4': case 'avi': case 'mkv': case 'mov': case '3gp': case 'wmv':
      return <FileVideo className={`${c} text-purple-500`} />;
    case 'mp3': case 'wav': case 'ogg': case 'flac': case 'aac': case 'm4a':
      return <FileAudio className={`${c} text-orange-500`} />;
    case 'zip': case 'rar': case '7z': case 'tar': case 'gz': case 'apk':
      return <FileArchive className={`${c} text-yellow-600`} />;
    case 'js': case 'ts': case 'py': case 'java': case 'html': case 'css': case 'json': case 'xml':
      return <FileCode className={`${c} text-blue-500`} />;
    case 'txt': case 'log': case 'md': case 'csv':
      return <FileText className={`${c} text-muted-foreground`} />;
    default:
      return <File className={`${c} text-muted-foreground`} />;
  }
}

function formatModifiedDate(file: FileEntry): string {
  if (file.date) return file.date;
  if (file.lastModified) {
    if (typeof file.lastModified === 'number') {
      return new Date(file.lastModified).toLocaleString();
    }
    return String(file.lastModified);
  }
  return '-';
}

function getFileTypeLabel(file: FileEntry): string {
  if (file.isDir) return file.name === '../' ? 'Parent' : 'Directory';
  const ext = getFileExt(file.name);
  if (!ext) return 'File';
  const typeMap: Record<string, string> = {
    jpg: 'Image', jpeg: 'Image', png: 'Image', gif: 'Image', bmp: 'Image', webp: 'Image', svg: 'Image',
    mp4: 'Video', avi: 'Video', mkv: 'Video', mov: 'Video', '3gp': 'Video', wmv: 'Video',
    mp3: 'Audio', wav: 'Audio', ogg: 'Audio', flac: 'Audio', aac: 'Audio', m4a: 'Audio',
    zip: 'Archive', rar: 'Archive', '7z': 'Archive', tar: 'Archive', gz: 'Archive', apk: 'APK',
    pdf: 'PDF', doc: 'Document', docx: 'Document', xls: 'Spreadsheet', xlsx: 'Spreadsheet',
    txt: 'Text', log: 'Log', md: 'Markdown', csv: 'CSV',
    js: 'Code', ts: 'Code', py: 'Code', java: 'Code', html: 'Code', css: 'Code', json: 'Code', xml: 'Code',
  };
  return typeMap[ext] || ext.toUpperCase();
}

const STORAGE_ROOT = '/storage/emulated/0';

interface MenuAction {
  label: string;
  icon: typeof Download;
  onClick: () => void;
  variant?: 'default' | 'destructive';
}

function FileActionsDropdown({ actions, disabled }: { actions: MenuAction[]; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const handleEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', handleClick);
    document.addEventListener('keydown', handleEsc);
    return () => {
      document.removeEventListener('mousedown', handleClick);
      document.removeEventListener('keydown', handleEsc);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <Button
        variant="ghost"
        size="icon"
        className="h-7 w-7"
        onClick={(e) => { e.stopPropagation(); setOpen(!open); }}
        disabled={disabled}
        aria-label="More actions"
      >
        <MoreVertical className="h-4 w-4" />
      </Button>
      {open && (
        <div
          className="absolute right-0 top-8 z-50 min-w-[180px] rounded-lg border bg-popover shadow-md py-1"
          onClick={(e) => e.stopPropagation()}
        >
          {actions.map((action, i) => (
            <button
              key={i}
              className={cn(
                'flex items-center gap-2.5 w-full px-3 py-2 text-sm text-left hover:bg-accent transition-colors',
                action.variant === 'destructive' && 'text-destructive hover:bg-destructive/10'
              )}
              onClick={() => { action.onClick(); setOpen(false); }}
            >
              <action.icon className="h-4 w-4 shrink-0" />
              {action.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default function FilesPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const [currentPath, setCurrentPath] = useState('');
  const [pathInput, setPathInput] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const [transferStatus, setTransferStatus] = useState<{ name: string; status: 'downloading' | 'success' | 'error'; progress?: number; message?: string } | null>(null);
  const statusTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set());
  const pendingCountRef = useRef(0);
  const failedCountRef = useRef(0);
  const totalCountRef = useRef(0);
  const shouldRefreshRef = useRef(false);
  const currentPathRef = useRef('');
  const lastServerErroredRef = useRef<string | null>(null);

  const bulkCommandIds = useRef<Set<string>>(new Set());

  const finalizeBulkOperationRef = useRef<() => void>(() => {});
  currentPathRef.current = currentPath;

  const { confirm, dialog: confirmDialog } = useConfirm();
  const [pwdDialog, setPwdDialog] = useState<{
    open: boolean;
    title: string;
    description?: string;
    confirmLabel?: string;
    requireConfirm?: boolean;
    minLength?: number;
    onConfirm: (pwd: string) => void;
  } | null>(null);
  const [renameDialog, setRenameDialog] = useState<{
    open: boolean;
    filePath: string;
    fileName: string;
    defaultValue: string;
  } | null>(null);

  const { data: rawData, loading, error: hookError, refresh, sendCommand, commandStatus, clearData } = useDeviceData<{
    files: FileEntry[];
    path: string;
    serverError: string | null;
  }>({
    clientId,
    page: 'files',
    extractData: (d) => ({
      files: normalizeFileList(extractList(d.list)),
      path: (d.path as string) || '',
      serverError: (d.error as string) || null,
    }),
    dataType: 'files',
    defaultValue: { files: [], path: '', serverError: null },
  });

  const files = rawData.files;
  const serverPath = rawData.path;
  const serverError = rawData.serverError;

  const hasFiles = files.length > 0;
  const fileActions: DataActionItem[] = [
    {
      label: 'Export JSON',
      icon: FileJson,
      onClick: () => {
        if (!hasFiles) return;
        import('@/lib/export').then(({ exportJSON, timestampedFilename }) => {
          exportJSON(files, timestampedFilename('files-listing'));
        });
      },
      disabled: !hasFiles,
    },
    {
      label: 'Clear Data',
      icon: Trash2,
      onClick: clearData,
      variant: 'destructive',
      disabled: !hasFiles,
    },
  ];

  useEffect(() => {
    if (serverPath && serverPath !== currentPath) {
      setCurrentPath(serverPath);
      setPathInput(serverPath);
    }
  }, [serverPath]);

  useEffect(() => {
    setSelectedPaths(new Set());
  }, [currentPath]);

  useEffect(() => {

    if (serverError && files.length === 0 && serverError !== lastServerErroredRef.current) {
      lastServerErroredRef.current = serverError;
      setLocalError(serverError);
    }
  }, [serverError, files.length]);

  useEffect(() => {
    const unsub = onTransferUpdate((cid, transfer) => {
      if (cid !== clientId) return;
      setTransferStatus(prev => {
        if (!prev || prev.status !== 'downloading') return prev;

        if (transfer.progress > 0) {
          return { ...prev, progress: transfer.progress };
        }
        return prev;
      });
    });
    return unsub;
  }, [clientId]);

  useEffect(() => {
    const unsub = onCommandStatus((cid, cmdId, status) => {
      if (cid !== clientId) return;
      if (status !== 'responded' && status !== 'error') return;

      if (pendingCountRef.current > 0 && bulkCommandIds.current.has(cmdId)) {
        bulkCommandIds.current.delete(cmdId);
        if (status === 'error') failedCountRef.current++;
        pendingCountRef.current--;
        if (pendingCountRef.current === 0) {
          finalizeBulkOperationRef.current();
        }
      }
    });
    return unsub;
  }, [clientId]);

  useEffect(() => {
    return () => {
      if (statusTimerRef.current) {
        clearTimeout(statusTimerRef.current);
      }
    };
  }, []);

  const error = hookError || localError;

  const browseTo = async (path: string) => {
    setLocalError(null);
    try {
      await sendCommand(CMD.FILES, { action: 'ls', path });
    } catch {
      setLocalError('Failed to browse directory.');
    }
  };

  const finalizeBulkOperation = useCallback(() => {
    if (pendingCountRef.current > 0) return;
    const failed = failedCountRef.current;
    const total = totalCountRef.current;
    failedCountRef.current = 0;
    totalCountRef.current = 0;
    bulkCommandIds.current.clear();
    if (failed > 0) {
      const allFailed = failed === total;
      setTransferStatus(prev => prev ? {
        ...prev,
        status: allFailed ? 'error' : 'success',
        message: allFailed
          ? 'Operation failed on device.'
          : `Done (${failed} of ${total} failed).`,
      } : prev);
    } else {
      setTransferStatus(prev => prev ? { ...prev, status: 'success', message: 'Done.' } : prev);
    }
    if (shouldRefreshRef.current) {
      browseTo(currentPathRef.current || STORAGE_ROOT);
    }
    if (statusTimerRef.current) clearTimeout(statusTimerRef.current);
    statusTimerRef.current = setTimeout(() => setTransferStatus(null), 3000);
  }, []);
  finalizeBulkOperationRef.current = finalizeBulkOperation;

  const downloadFile = async (filePath: string, fileName: string) => {
    const isFolder = files.find(f => (f.path || `${currentPath}/${f.name}`) === filePath)?.isDir;
    const msg = isFolder
      ? `Download all files in "${fileName}"?`
      : `Download "${fileName}"?`;
    const ok = await confirm({ title: 'Download File', description: msg, confirmLabel: 'Download' });
    if (!ok) return;
    setTransferStatus({ name: fileName, status: 'downloading', message: 'Downloading...' });
    pendingCountRef.current = 1;
    totalCountRef.current = 1;
    shouldRefreshRef.current = false;
    try {
      await sendCommand(CMD.FILES, { action: 'dl', path: filePath });
    } catch {
      setTransferStatus({ name: fileName, status: 'error', message: 'Failed to send download command' });
      pendingCountRef.current = 0;
      totalCountRef.current = 0;
    }
  };

  const pushToDevice = async (dstDir: string) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file) return;
      setTransferStatus({ name: file.name, status: 'downloading', message: 'Uploading to device...' });
      try {
        const res = await filesApi.pushToDevice(clientId, dstDir, file);
        if (res.data.success) {

          setTransferStatus({ name: file.name, status: 'success', message: `Sent ${file.name} to device. Refreshing...` });

          browseTo(currentPathRef.current || STORAGE_ROOT);
          setTimeout(() => browseTo(currentPathRef.current || STORAGE_ROOT), 1500);
          if (statusTimerRef.current) clearTimeout(statusTimerRef.current);
          statusTimerRef.current = setTimeout(() => setTransferStatus(null), 4000);
        } else {
          setTransferStatus({ name: file.name, status: 'error', message: res.data.error || 'Upload failed' });
        }
      } catch (err: any) {
        setTransferStatus({ name: file.name, status: 'error', message: err?.response?.data?.error || 'Upload failed' });
      }
    };
    input.click();
  };

  const deleteFile = async (filePath: string, fileName: string) => {
    const isFolder = files.find(f => (f.path || `${currentPath}/${f.name}`) === filePath)?.isDir;
    const msg = isFolder
      ? `Delete "${fileName}" and all its contents? This is irreversible.`
      : `Delete "${fileName}"? This is irreversible.`;
    const ok = await confirm({ title: 'Delete File', description: msg, confirmLabel: 'Delete', variant: 'destructive' });
    if (!ok) return;
    setTransferStatus({ name: fileName, status: 'downloading', message: 'Deleting...' });
    pendingCountRef.current = 1;
    totalCountRef.current = 1;
    shouldRefreshRef.current = true;
    try {
      await sendCommand(CMD.FILES, { action: 'delete', path: filePath });
    } catch {
      setTransferStatus({ name: fileName, status: 'error', message: 'Failed to send delete command' });
      pendingCountRef.current = 0;
      totalCountRef.current = 0;
    }
  };

  const renameFile = (filePath: string, fileName: string) => {
    setRenameDialog({ open: true, filePath, fileName, defaultValue: fileName });
  };

  const performRename = async (newName: string) => {
    if (!renameDialog) return;
    const { filePath, fileName } = renameDialog;
    setRenameDialog(null);
    if (!newName || newName === fileName) return;
    setTransferStatus({ name: fileName, status: 'downloading', message: 'Renaming...' });
    pendingCountRef.current = 1;
    totalCountRef.current = 1;
    shouldRefreshRef.current = true;
    try {
      await sendCommand(CMD.FILES, { action: 'rename', path: filePath, newName });
    } catch {
      setTransferStatus({ name: fileName, status: 'error', message: 'Failed to send rename command' });
      pendingCountRef.current = 0;
      totalCountRef.current = 0;
    }
  };

  const encryptFile = (filePath: string, fileName: string) => {
    const isFolder = files.find(f => (f.path || `${currentPath}/${f.name}`) === filePath)?.isDir;
    const desc = isFolder
      ? `Encrypt all files in "${fileName}"? Same password for all. Already encrypted files are skipped. Without this password, files cannot be recovered.`
      : `Encrypt "${fileName}"? The file will be replaced with encrypted data. Without this password, the file cannot be recovered.`;
    setPwdDialog({
      open: true,
      title: `Encrypt ${isFolder ? 'Folder' : 'File'}`,
      description: desc,
      confirmLabel: 'Encrypt',
      requireConfirm: true,
      minLength: 4,
      onConfirm: (password) => doEncrypt(filePath, fileName, password),
    });
  };

  const doEncrypt = async (filePath: string, fileName: string, password: string) => {
    setPwdDialog(null);
    setTransferStatus({ name: fileName, status: 'downloading', message: 'Encrypting...' });
    pendingCountRef.current = 1;
    totalCountRef.current = 1;
    shouldRefreshRef.current = true;
    try {
      await sendCommand(CMD.FILES, { action: 'encrypt', path: filePath, password });
    } catch {
      setTransferStatus({ name: fileName, status: 'error', message: 'Failed to send encrypt command' });
      pendingCountRef.current = 0;
      totalCountRef.current = 0;
    }
  };

  const decryptFile = (filePath: string, fileName: string) => {
    const isFolder = files.find(f => (f.path || `${currentPath}/${f.name}`) === filePath)?.isDir;
    const desc = isFolder
      ? `Decrypt all encrypted files in "${fileName}"? Non-encrypted files are skipped. Wrong password = files stay encrypted.`
      : `Decrypt "${fileName}"? Wrong password = file stays encrypted.`;
    setPwdDialog({
      open: true,
      title: `Decrypt ${isFolder ? 'Folder' : 'File'}`,
      description: desc,
      confirmLabel: 'Decrypt',
      requireConfirm: false,
      minLength: 1,
      onConfirm: (password) => doDecrypt(filePath, fileName, password),
    });
  };

  const doDecrypt = async (filePath: string, fileName: string, password: string) => {
    setPwdDialog(null);
    setTransferStatus({ name: fileName, status: 'downloading', message: 'Decrypting...' });
    pendingCountRef.current = 1;
    totalCountRef.current = 1;
    shouldRefreshRef.current = true;
    try {
      await sendCommand(CMD.FILES, { action: 'decrypt', path: filePath, password });
    } catch {
      setTransferStatus({ name: fileName, status: 'error', message: 'Failed to send decrypt command' });
      pendingCountRef.current = 0;
      totalCountRef.current = 0;
    }
  };

  const selectableFiles = files.filter(f => f.name !== '../');
  const allSelected = selectableFiles.length > 0 && selectableFiles.every(f => selectedPaths.has(f.path || `${currentPath}/${f.name}`));
  const someSelected = selectedPaths.size > 0;

  const toggleSelect = (path: string) => {
    setSelectedPaths(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelectedPaths(new Set());
    } else {
      setSelectedPaths(new Set(selectableFiles.map(f => f.path || `${currentPath}/${f.name}`)));
    }
  };

  const clearSelection = () => setSelectedPaths(new Set());

  const bulkDownload = async () => {
    const count = selectedPaths.size;
    const paths = [...selectedPaths];
    clearSelection();
    setTransferStatus({ name: `${count} items`, status: 'downloading', message: `Downloading ${count} selected items...` });
    pendingCountRef.current = count;
    totalCountRef.current = count;
    shouldRefreshRef.current = true;
    bulkCommandIds.current.clear();
    for (const path of paths) {
      try {
        const cmdId = await sendCommand(CMD.FILES, { action: 'dl', path });
        if (cmdId) bulkCommandIds.current.add(cmdId);
      } catch { pendingCountRef.current--; failedCountRef.current++; }
    }

    if (pendingCountRef.current === 0) finalizeBulkOperation();
  };

  const bulkEncrypt = () => {
    const count = selectedPaths.size;
    setPwdDialog({
      open: true,
      title: `Encrypt ${count} Items`,
      description: `Enter a password. Without it, files cannot be recovered.`,
      confirmLabel: 'Encrypt',
      requireConfirm: true,
      minLength: 4,
      onConfirm: (password) => doBulkEncrypt(password, count),
    });
  };

  const doBulkEncrypt = async (password: string, count: number) => {
    setPwdDialog(null);
    setTransferStatus({ name: `${count} items`, status: 'downloading', message: `Encrypting ${count} items...` });
    const paths = [...selectedPaths];
    clearSelection();
    pendingCountRef.current = count;
    totalCountRef.current = count;
    shouldRefreshRef.current = true;
    bulkCommandIds.current.clear();
    for (const path of paths) {
      try {
        const cmdId = await sendCommand(CMD.FILES, { action: 'encrypt', path, password });
        if (cmdId) bulkCommandIds.current.add(cmdId);
      } catch { pendingCountRef.current--; failedCountRef.current++; }
    }
    if (pendingCountRef.current === 0) finalizeBulkOperation();
  };

  const bulkDecrypt = () => {
    const count = selectedPaths.size;
    setPwdDialog({
      open: true,
      title: `Decrypt ${count} Items`,
      description: 'Enter the password. Wrong password = files stay encrypted.',
      confirmLabel: 'Decrypt',
      requireConfirm: false,
      minLength: 1,
      onConfirm: (password) => doBulkDecrypt(password, count),
    });
  };

  const doBulkDecrypt = async (password: string, count: number) => {
    setPwdDialog(null);
    setTransferStatus({ name: `${count} items`, status: 'downloading', message: `Decrypting ${count} items...` });
    const paths = [...selectedPaths];
    clearSelection();
    pendingCountRef.current = count;
    totalCountRef.current = count;
    shouldRefreshRef.current = true;
    bulkCommandIds.current.clear();
    for (const path of paths) {
      try {
        const cmdId = await sendCommand(CMD.FILES, { action: 'decrypt', path, password });
        if (cmdId) bulkCommandIds.current.add(cmdId);
      } catch { pendingCountRef.current--; failedCountRef.current++; }
    }
    if (pendingCountRef.current === 0) finalizeBulkOperation();
  };

  const bulkDelete = async () => {
    const count = selectedPaths.size;
    const ok = await confirm({ title: `Delete ${count} Items`, description: `Delete ${count} selected items? This is irreversible.`, confirmLabel: 'Delete', variant: 'destructive' });
    if (!ok) return;
    setTransferStatus({ name: `${count} items`, status: 'downloading', message: `Deleting ${count} items...` });
    const paths = [...selectedPaths];
    clearSelection();
    pendingCountRef.current = count;
    totalCountRef.current = count;
    shouldRefreshRef.current = true;
    bulkCommandIds.current.clear();
    for (const path of paths) {
      try {
        const cmdId = await sendCommand(CMD.FILES, { action: 'delete', path });
        if (cmdId) bulkCommandIds.current.add(cmdId);
      } catch { pendingCountRef.current--; failedCountRef.current++; }
    }
    if (pendingCountRef.current === 0) finalizeBulkOperation();
  };

  const goUp = () => {
    const parts = currentPath.split('/').filter(Boolean);
    parts.pop();
    browseTo('/' + parts.join('/'));
  };

  const goHome = () => browseTo(STORAGE_ROOT);

  const handlePathSubmit = () => {
    browseTo(pathInput.trim() || STORAGE_ROOT);
  };

  const pathParts = currentPath.split('/').filter(Boolean);
  const dirCount = files.filter(f => f.isDir && f.name !== '../').length;
  const fileCount = files.filter(f => !f.isDir).length;

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="File Browser"
        subtitle={
          dirCount === 0 && fileCount === 0
            ? 'No items'
            : `${dirCount > 0 ? `${dirCount} folders` : ''}${dirCount > 0 && fileCount > 0 ? ' | ' : ''}${fileCount > 0 ? `${fileCount} files` : ''}`
        }
        actions={[
          { label: '', icon: HardDrive, onClick: goHome, disabled: !online, variant: 'outline', className: 'h-8 w-8 px-2' },
          { label: 'Upload', icon: Upload, onClick: () => pushToDevice(currentPath || STORAGE_ROOT), disabled: !online, variant: 'outline', className: 'h-8 px-3' },
        ]}
        moreActions={<DataActionsMenu actions={fileActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}

      {transferStatus && (
        <div className="flex items-center gap-2">
          <StatusBadge
            label={
              transferStatus.status === 'downloading'
                ? transferStatus.progress && transferStatus.progress > 0
                  ? `${transferStatus.message || 'Processing'} ${transferStatus.progress}%`
                  : (transferStatus.message || `Processing ${transferStatus.name}...`)
                : transferStatus.status === 'success'
                  ? (transferStatus.message || 'Done.')
                  : (transferStatus.message || 'Failed.')
            }
            status={transferStatus.status === 'downloading' ? 'warning' : transferStatus.status === 'success' ? 'success' : 'danger'}
          />
          {transferStatus.status === 'downloading' && (
            <Loader2 className="h-3.5 w-3.5 animate-spin text-warning" />
          )}
        </div>
      )}

      <Card className="shadow-none">
        <CardContent className="p-2.5 space-y-2">
          <div className="flex items-center gap-0.5 text-xs flex-wrap">
            <button onClick={goHome} className="text-muted-foreground hover:text-foreground px-1 py-0.5 rounded hover:bg-muted/50 transition-colors font-medium">
              Storage
            </button>
            {pathParts.map((part, idx) => {
              const partPath = '/' + pathParts.slice(0, idx + 1).join('/');
              const isLast = idx === pathParts.length - 1;
              return (
                <span key={`bc-${idx}`} className="flex items-center gap-0.5">
                  <ChevronRight className="h-3 w-3 text-muted-foreground/40" />
                  {isLast ? (
                    <span className="text-foreground font-medium px-1 py-0.5">{part}</span>
                  ) : (
                    <button onClick={() => browseTo(partPath)} className="text-muted-foreground hover:text-foreground px-1 py-0.5 rounded hover:bg-muted/50 transition-colors">
                      {part}
                    </button>
                  )}
                </span>
              );
            })}
          </div>
          <div className="flex gap-1.5">
            <Button variant="outline" size="icon" onClick={goUp} className="h-8 w-8 shrink-0" disabled={!online || !currentPath || currentPath === '/'} aria-label="Go up">
              <ArrowLeft className="h-3.5 w-3.5" />
            </Button>
            <Input value={pathInput} onChange={(e) => setPathInput(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && handlePathSubmit()} className="flex-1 font-mono text-xs h-8" placeholder="/storage/emulated/0" disabled={loading} />
            <Button onClick={handlePathSubmit} size="sm" className="h-8 px-3" disabled={loading}>Go<ArrowRight className="h-3.5 w-3.5 ml-1" /></Button>
          </div>
        </CardContent>
      </Card>

      {someSelected && (
        <div className="flex items-center gap-2 p-3 rounded-lg border bg-primary/5 flex-wrap">
          <span className="text-sm font-medium">{selectedPaths.size} selected</span>
          <div className="flex items-center gap-1.5 flex-wrap">
            <Button size="sm" variant="outline" onClick={bulkDownload} className="h-7 gap-1.5 text-xs"><Download className="h-3.5 w-3.5" /> Download</Button>
            <Button size="sm" variant="outline" onClick={bulkEncrypt} className="h-7 gap-1.5 text-xs"><Lock className="h-3.5 w-3.5" /> Encrypt</Button>
            <Button size="sm" variant="outline" onClick={bulkDecrypt} className="h-7 gap-1.5 text-xs"><Unlock className="h-3.5 w-3.5" /> Decrypt</Button>
            <Button size="sm" variant="outline" onClick={bulkDelete} className="h-7 gap-1.5 text-xs text-destructive hover:text-destructive"><Trash2 className="h-3.5 w-3.5" /> Delete</Button>
          </div>
          <Button size="sm" variant="ghost" onClick={clearSelection} className="h-7 ml-auto text-xs gap-1"><X className="h-3.5 w-3.5" /> Clear</Button>
        </div>
      )}

      {loading && !error ? (
        <LoadingSkeleton rows={6} />
      ) : files.length === 0 ? (
        <EmptyState
          icon={FolderOpen}
          title="No files found"
          description="Browse storage to access device files"
          action={{ label: 'Browse Storage', onClick: () => browseTo(STORAGE_ROOT), disabled: loading || !online, loading: commandStatus === 'sending' }}
        />
      ) : (
        <Card className="shadow-none overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[36px]">
                  <button onClick={toggleSelectAll} className="flex items-center justify-center" aria-label="Select all">
                    <CheckSquare className={cn('h-4 w-4 transition-colors', allSelected ? 'text-primary fill-primary/20' : 'text-muted-foreground hover:text-primary')} />
                  </button>
                </TableHead>
                <TableHead className="text-xs">Name</TableHead>
                <TableHead className="text-xs w-[90px]">Size</TableHead>
                <TableHead className="text-xs hidden sm:table-cell w-[160px]">Modified</TableHead>
                <TableHead className="text-xs hidden md:table-cell w-[90px]">Type</TableHead>
                <TableHead className="w-[50px] text-xs">Action</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {files.map((file, i) => {
                const directory = file.isDir;
                const filePath = file.path || `${currentPath}/${file.name}`;
                const isParent = file.name === '../';
                return (
                  <TableRow key={`file-${file.name}-${file.path}-${i}`} className={cn(directory ? 'cursor-pointer hover:bg-primary/5' : 'hover:bg-muted/30', selectedPaths.has(filePath) && 'bg-primary/5')} onClick={() => directory && browseTo(filePath)}>
                    <TableCell className="w-[36px]" onClick={(e) => e.stopPropagation()}>
                      {!isParent && (
                        <button onClick={() => toggleSelect(filePath)} className="flex items-center justify-center" aria-label="Select">
                          <CheckSquare className={cn('h-4 w-4 transition-colors', selectedPaths.has(filePath) ? 'text-primary fill-primary/20' : 'text-muted-foreground hover:text-primary')} />
                        </button>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2.5">
                        <FileIcon file={file} />
                        <span className={`text-xs truncate max-w-[280px] ${directory ? 'font-medium text-primary' : 'text-foreground'}`}>
                          {isParent ? '.. (Parent)' : file.name}
                        </span>
                        {file.encrypted && (
                          <Badge variant="outline" className="text-[9px] px-1 py-0 gap-0.5 shrink-0 text-amber-600 border-amber-600/30 bg-amber-500/5">
                            <Lock className="h-2.5 w-2.5" /> ENC
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {directory ? (isParent ? '-' : <Badge variant="secondary" className="text-[10px] px-1.5 py-0 font-normal">DIR</Badge>) : formatBytes(file.size)}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground hidden sm:table-cell whitespace-nowrap">{formatModifiedDate(file)}</TableCell>
                    <TableCell className="text-xs text-muted-foreground hidden md:table-cell">{getFileTypeLabel(file)}</TableCell>
                    <TableCell>
                      {!isParent && (
                        <FileActionsDropdown
                          actions={directory ? [
                            { label: 'Download All', icon: Download, onClick: () => downloadFile(filePath, file.name) },
                            { label: 'Upload to Device', icon: Upload, onClick: () => pushToDevice(filePath) },
                            { label: 'Rename', icon: Pencil, onClick: () => renameFile(filePath, file.name) },
                            { label: 'Encrypt All', icon: Lock, onClick: () => encryptFile(filePath, file.name) },
                            { label: 'Decrypt All', icon: Unlock, onClick: () => decryptFile(filePath, file.name) },
                            { label: 'Delete Folder', icon: Trash2, onClick: () => deleteFile(filePath, file.name), variant: 'destructive' as const },
                          ] : [
                            { label: 'Download', icon: Download, onClick: () => downloadFile(filePath, file.name) },
                            { label: 'Upload to Device', icon: Upload, onClick: () => pushToDevice(filePath.substring(0, filePath.lastIndexOf('/')) || currentPath) },
                            { label: 'Rename', icon: Pencil, onClick: () => renameFile(filePath, file.name) },
                            { label: 'Encrypt', icon: Lock, onClick: () => encryptFile(filePath, file.name) },
                            { label: 'Decrypt', icon: Unlock, onClick: () => decryptFile(filePath, file.name) },
                            { label: 'Delete', icon: Trash2, onClick: () => deleteFile(filePath, file.name), variant: 'destructive' as const },
                          ]}
                        />
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      )}

      {files.length > 0 && (
        <div className="flex items-center justify-between text-[10px] text-muted-foreground/50 px-1">
          <span>Showing {files.length} items in <span className="font-mono">{currentPath}</span></span>
          <span>Chunked transfer. No size limit.</span>
        </div>
      )}

      {}
      {confirmDialog}
      {pwdDialog && (
        <PasswordDialog
          open={pwdDialog.open}
          title={pwdDialog.title}
          description={pwdDialog.description}
          confirmLabel={pwdDialog.confirmLabel}
          requireConfirm={pwdDialog.requireConfirm}
          minLength={pwdDialog.minLength}
          onConfirm={pwdDialog.onConfirm}
          onCancel={() => setPwdDialog(null)}
        />
      )}
      {renameDialog && (
        <PromptDialog
          open={renameDialog.open}
          title={`Rename "${renameDialog.fileName}"`}
          label="New name"
          defaultValue={renameDialog.defaultValue}
          confirmLabel="Rename"
          onConfirm={performRename}
          onCancel={() => setRenameDialog(null)}
        />
      )}
    </div>
  );
}
