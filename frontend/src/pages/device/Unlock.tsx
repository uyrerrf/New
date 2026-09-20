import { useState, useEffect, useCallback, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Lock, Unlock, Loader2, Smartphone, LockOpen, RefreshCw } from 'lucide-react';
import { onDeviceUnlockUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard,
} from '@/components/device/shared';
import { useConfirm } from '@/components/ConfirmDialog';
import type { DeviceOutletContext } from '@/types';
import type { Socket } from 'socket.io-client';

type BusyAction = 'unlock' | 'lock' | null;

export default function UnlockPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pin, setPin] = useState('');
  const [connected, setConnected] = useState<boolean | null>(null);
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [locked, setLocked] = useState<boolean | null>(null);
  const [busy, setBusy] = useState<BusyAction>(null);
  const [result, setResult] = useState<string | null>(null);
  const { confirm, dialog: confirmDialog } = useConfirm();
  const [socketConnected, setSocketConnected] = useState<boolean>(!!getAdminSocket()?.connected);

  const busyRef = useRef<BusyAction>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resultTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then(() => setLoading(false))
      .catch((err: any) => {
        setError(err?.response?.status === 404 ? 'Device not found' : 'Failed to load device');
        setLoading(false);
      });
    clientsApi.sendCommand(id, CMD.DEVICE_UNLOCK, { action: 'status' }).catch((err: any) => {
      setError(err?.response?.data?.error || 'Failed to query device status');
    });
  }, [id]);

  useEffect(() => {
    if (!id) return;
    const unsub = onDeviceUnlockUpdate((data: any) => {
      if (!data || data.id !== id) return;
      const clearBusy = () => {
        if (busyRef.current) {
          setBusy(null);
          busyRef.current = null;
          if (timeoutRef.current) {
            clearTimeout(timeoutRef.current);
            timeoutRef.current = null;
          }
        }
      };
      if (data.type === 'status') {
        setEnabled(data.enabled === true);
        setConnected(data.connected === true);
        if (data.locked !== undefined) setLocked(data.locked === true);
      } else if (data.type === 'unlocked') {
        clearBusy();
        setLocked(false);
        showResult(data.message || 'Device unlocked');
      } else if (data.type === 'unlock_failed') {
        clearBusy();
        setLocked(data.locked === true);
        setError(data.message || 'Unlock failed. Try a different method.');
      } else if (data.type === 'locked') {
        clearBusy();
        setLocked(true);
        showResult(data.message || 'Device locked');
      } else if (data.type === 'lock_failed') {
        clearBusy();
        setError(data.message || 'Lock failed. Requires Android 9+.');
      } else if (data.type === 'error') {
        clearBusy();
        setError(data.error || data.message || 'Operation failed');
      }
    });
    return unsub;
  }, [id]);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      if (resultTimerRef.current) clearTimeout(resultTimerRef.current);
    };
  }, []);

  const showResult = useCallback((msg: string) => {
    setResult(msg);
    if (resultTimerRef.current) clearTimeout(resultTimerRef.current);
    resultTimerRef.current = setTimeout(() => setResult(null), 5000);
  }, []);

  const doUnlock = useCallback(async () => {
    if (!id) return;
    setError(null); setResult(null);
    setBusy('unlock'); busyRef.current = 'unlock';
    timeoutRef.current = setTimeout(() => {
      if (busyRef.current === 'unlock') {
        setBusy(null); busyRef.current = null;
        setError('Unlock timed out. Device may be offline or Accessibility is off.');
        clientsApi.sendCommand(id, CMD.DEVICE_UNLOCK, { action: 'cancel' }).catch(() => {});
      }
    }, 30000);
    try {
      await clientsApi.sendCommand(id, CMD.DEVICE_UNLOCK, { action: 'unlock', pin: pin || undefined });
    } catch (err: any) {
      if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
      setBusy(null); busyRef.current = null;
      setError(err?.response?.data?.error || 'Failed to send unlock command');
    }
  }, [id, pin]);

  const doLock = useCallback(async () => {
    if (!id) return;
    const ok = await confirm({ title: 'Lock Device', description: 'Lock the device screen remotely?', confirmLabel: 'Lock' });
    if (!ok) return;
    setError(null); setResult(null);
    setBusy('lock'); busyRef.current = 'lock';
    timeoutRef.current = setTimeout(() => {
      if (busyRef.current === 'lock') {
        setBusy(null); busyRef.current = null;
        setError('Lock timed out. Device may be offline.');
      }
    }, 15000);
    try {
      await clientsApi.sendCommand(id, CMD.DEVICE_UNLOCK, { action: 'lock' });
    } catch (err: any) {
      if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
      setBusy(null); busyRef.current = null;
      setError(err?.response?.data?.error || 'Failed to send lock command');
    }
  }, [id]);

  const refreshStatus = useCallback(async () => {
    if (!id) return;
    setError(null);
    try {
      await clientsApi.sendCommand(id, CMD.DEVICE_UNLOCK, { action: 'status' });
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to refresh status');
    }
  }, [id]);

  const canAct = online && socketConnected && busy === null;

  if (loading) return <LoadingSkeleton rows={2} />;

  const isLocked = locked === true;
  const isUnlocked = locked === false;
  const serviceReady = enabled !== false && connected === true;

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Remote Lock & Unlock"
        subtitle="Control the device screen lock"
        actions={[
          { label: 'Refresh', icon: RefreshCw, onClick: refreshStatus, disabled: !canAct, variant: 'outline' as const },
        ]}
      />

      {error && <ErrorAlert message={error} onRetry={refreshStatus} />}

      {result && (
        <div className="p-3 rounded-lg bg-success/10 border border-success/20 text-success text-sm flex items-center gap-2">
          <Unlock className="h-4 w-4 shrink-0" /> {result}
        </div>
      )}

      <SectionCard title="Status" icon={Smartphone}>
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className={`h-12 w-12 rounded-xl flex items-center justify-center shrink-0 ${isLocked ? 'bg-destructive/10' : isUnlocked ? 'bg-success/10' : 'bg-muted'}`}>
              {isLocked ? <Lock className="h-6 w-6 text-destructive" /> : isUnlocked ? <Unlock className="h-6 w-6 text-success" /> : <Smartphone className="h-6 w-6 text-muted-foreground" />}
            </div>
            <div>
              <p className="text-sm font-semibold">
                {isLocked ? 'Locked' : isUnlocked ? 'Unlocked' : 'Unknown'}
              </p>
              <p className="text-xs text-muted-foreground">
                {serviceReady ? 'Service ready' : enabled === false ? 'Service off' : 'Not ready'}
              </p>
            </div>
          </div>
          <StatusBadge
            label={isLocked ? 'Locked' : isUnlocked ? 'Unlocked' : 'Unknown'}
            status={isLocked ? 'danger' : isUnlocked ? 'success' : 'neutral'}
          />
        </div>
        {!online && (
          <div className="mt-3 pt-3 border-t">
            <StatusBadge label="Device Offline" status="danger" />
          </div>
        )}
      </SectionCard>

      <SectionCard title="Unlock" icon={LockOpen}>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="pin-input" className="text-xs">PIN / Pattern (optional)</Label>
            <Input
              id="pin-input"
              type="password"
              placeholder="PIN digits or pattern nodes (1-9)"
              value={pin}
              onChange={e => setPin(e.target.value.replace(/[^0-9]/g, ''))}
              disabled={busy !== null}
              maxLength={16}
              className="h-9"
            />
            <p className="text-[11px] text-muted-foreground">
              Leave empty for swipe unlock. PIN: digits only. Pattern: nodes 1-9.
            </p>
          </div>
          <Button onClick={doUnlock} disabled={!canAct} className="gap-2 w-full" size="sm">
            {busy === 'unlock' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Unlock className="h-4 w-4" />}
            {busy === 'unlock' ? 'Unlocking...' : 'Unlock Device'}
          </Button>
        </div>
      </SectionCard>

      <SectionCard title="Lock" icon={Lock}>
        <Button onClick={doLock} variant="outline" disabled={!canAct} className="gap-2 w-full" size="sm">
          {busy === 'lock' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Lock className="h-4 w-4" />}
          {busy === 'lock' ? 'Locking...' : 'Lock Device'}
        </Button>
      </SectionCard>

      {confirmDialog}
    </div>
  );
}
