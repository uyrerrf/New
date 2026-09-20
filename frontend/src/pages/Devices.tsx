import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDevicesStore } from '@/store/devices';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Smartphone, Trash2, RefreshCw, ChevronRight, Search, MapPin, Clock, UserPlus, UserMinus, AlertCircle } from 'lucide-react';
import { clientsApi, usersApi } from '@/services/api';
import type { UserItem } from '@/types';
import { useConfirm } from '@/components/ConfirmDialog';
import { getCountryFlag, formatDate } from '@/lib/utils';

export default function DevicesPage() {
  const { onlineClients, offlineClients, isLoading, error: storeLoadError, fetchDashboard, deleteDevice } = useDevicesStore();
  const { hasPermission } = useAuthStore();
  const navigate = useNavigate();
  const [deleting, setDeleting] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [tab, setTab] = useState<'online' | 'offline' | 'all'>('all');
  const [assignDialog, setAssignDialog] = useState<{ clientId: string; currentOwnerId: string | null } | null>(null);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const [assigning, setAssigning] = useState(false);
  const { confirm, dialog: confirmDialog } = useConfirm();

  useEffect(() => {
    fetchDashboard();

    const interval = setInterval(() => fetchDashboard(true), 30000);
    return () => clearInterval(interval);
  }, [fetchDashboard]);

  useEffect(() => {
    if (hasPermission('users:manage')) {
      usersApi.getAll().then(res => {
        if (res.data.success) setUsers(res.data.data.filter((u: UserItem) => u.role === 'user'));
      }).catch(() => {  });
    }
  }, [hasPermission]);

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const ok = await confirm({ title: 'Delete Device', description: 'Are you sure you want to delete this device? All associated data will be removed.', confirmLabel: 'Delete', variant: 'destructive' });
    if (!ok) return;
    setDeleting(id);
    setActionError(null);

    const success = await deleteDevice(id);
    if (!success) {
      setActionError('Delete failed. Device may already be removed.');
    }
    setDeleting(null);
  };

  const openAssignDialog = async (clientId: string, currentOwnerId: string | null, e: React.MouseEvent) => {
    e.stopPropagation();

    setAssignError(null);
    try {
      const res = await usersApi.getAll();
      if (res.data.success) {
        setUsers(res.data.data.filter((u: UserItem) => u.role === 'user'));
        setSelectedUserId(currentOwnerId || '');
        setAssignDialog({ clientId, currentOwnerId });
      }
    } catch {}
  };

  const [assignError, setAssignError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const handleAssign = async () => {
    if (!assignDialog || !selectedUserId) return;
    setAssigning(true);
    setAssignError(null);
    try {
      await clientsApi.assign(assignDialog.clientId, selectedUserId);
      setAssignDialog(null);
      fetchDashboard();
    } catch (err: any) {
      setAssignError(err?.response?.data?.error || 'Failed to assign device');
    }
    setAssigning(false);
  };

  const handleUnassign = async (clientId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setActionError(null);
    try {
      await clientsApi.unassign(clientId);
      fetchDashboard();
    } catch (err: any) {

      setActionError(err?.response?.data?.error || 'Failed to unassign device');
    }
  };

  const getOwnerName = (ownerId: string | null) => {
    if (!ownerId) return null;
    const u = users.find(u => u.id === ownerId);

    return u ? u.username : null;
  };

  const allDevices = [...onlineClients, ...offlineClients];

  const filteredDevices = allDevices.filter((d) => {
    if (search) {
      const q = search.toLowerCase();
      return (
        d.id.toLowerCase().includes(q) ||
        (d.deviceModel || '').toLowerCase().includes(q) ||
        (d.deviceBrand || '').toLowerCase().includes(q) ||
        (d.ip || '').toLowerCase().includes(q) ||
        (d.city || '').toLowerCase().includes(q) ||
        (d.country || '').toLowerCase().includes(q)
      );
    }
    return true;
  });

  const displayedDevices = filteredDevices.filter((d) => {
    if (tab === 'online') return d.online;
    if (tab === 'offline') return !d.online;
    return true;
  });

  const tabs = [
    { key: 'all' as const, label: 'All', count: allDevices.length },
    { key: 'online' as const, label: 'Online', count: onlineClients.length },
    { key: 'offline' as const, label: 'Offline', count: offlineClients.length },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <Smartphone className="h-6 w-6 text-primary" />
            Devices
          </h1>
          <p className="text-muted-foreground mt-1">View and manage all connected devices.</p>
        </div>
        <Button onClick={() => fetchDashboard()} variant="outline" disabled={isLoading} className="self-start">
          <RefreshCw className={`h-4 w-4 mr-2 ${isLoading ? 'animate-spin' : ''}`} />
          Refresh
        </Button>
      </div>

      {(storeLoadError || actionError) && (
        <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
          <AlertCircle className="h-4 w-4 shrink-0" /> {actionError || storeLoadError}
          <Button variant="ghost" size="sm" className="ml-auto h-7 text-xs" onClick={() => { setActionError(null); fetchDashboard(); }}>Retry</Button>
        </div>
      )}

      <div className="flex flex-col sm:flex-row gap-4 items-start sm:items-center justify-between">
        <div className="flex gap-1 p-1 bg-muted rounded-lg">
          {tabs.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`px-3 py-1.5 text-sm font-medium rounded-md transition-colors ${
                tab === t.key
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {t.label}
              <span className={`ml-1.5 text-xs ${tab === t.key ? 'text-primary' : 'text-muted-foreground'}`}>
                ({t.count})
              </span>
            </button>
          ))}
        </div>
        <div className="relative w-full sm:w-72">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search devices..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
      </div>

      <Card className="shadow-sm">
        <CardContent className="p-0">
          {displayedDevices.length === 0 ? (
            <div className="text-center py-16 text-muted-foreground">
              <Smartphone className="h-12 w-12 mx-auto mb-3 opacity-50" />
              <p className="font-medium">
                {search ? 'No devices match your search' : 'No devices found'}
              </p>
              <p className="text-sm mt-1">
                {search ? 'Try a different search term' : 'Devices will appear here once they connect'}
              </p>
            </div>
          ) : (
            <>
              <div className="hidden md:block overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Device</TableHead>
                      <TableHead>Location</TableHead>
                      <TableHead>IP Address</TableHead>
                      <TableHead>Last Seen</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead className="w-[80px]">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {displayedDevices.map((client) => (
                      <TableRow
                        key={client.id}
                        className="cursor-pointer hover:bg-muted/50"
                        onClick={() => navigate(`/device/${client.id}/info`)}
                      >
                        <TableCell>
                          <div className="flex items-center gap-3">
                            <div className="h-10 w-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                              <Smartphone className="h-5 w-5 text-primary" />
                            </div>
                            <div>
                              <p className="font-medium">{client.deviceModel || 'Unknown'}</p>
                              <p className="text-xs text-muted-foreground">{client.deviceBrand || ''} {client.deviceVersion || ''}</p>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <span>{getCountryFlag(client.country)}</span>
                            <span>{client.city || client.country || 'Unknown'}</span>
                          </div>
                        </TableCell>
                        <TableCell className="font-mono text-xs">{client.ip}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">{formatDate(client.lastSeen)}</TableCell>
                        <TableCell>
                          {client.online ? (
                            <Badge className="bg-success text-white border-0">Online</Badge>
                          ) : (
                            <Badge variant="secondary">Offline</Badge>
                          )}
                        </TableCell>
                        <TableCell>
                          <div className="flex gap-1">
                            <Button variant="ghost" size="icon" onClick={(e) => { e.stopPropagation(); navigate(`/device/${client.id}/info`); }}>
                              <ChevronRight className="h-4 w-4" />
                            </Button>
                            {hasPermission('users:manage') && (
                              client.ownerId ? (
                                <Button variant="ghost" size="icon" onClick={(e) => handleUnassign(client.id, e)} title={`Assigned to ${getOwnerName(client.ownerId) || 'user'}. Click to unassign`}>
                                  <UserMinus className="h-4 w-4 text-muted-foreground" />
                                </Button>
                              ) : (
                                <Button variant="ghost" size="icon" onClick={(e) => openAssignDialog(client.id, client.ownerId, e)} title="Assign to user">
                                  <UserPlus className="h-4 w-4 text-muted-foreground" />
                                </Button>
                              )
                            )}
                            {hasPermission('device:delete') && (
                              <Button variant="ghost" size="icon" onClick={(e) => handleDelete(client.id, e)} disabled={deleting === client.id}>
                                <Trash2 className="h-4 w-4 text-destructive" />
                              </Button>
                            )}
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              <div className="md:hidden divide-y">
                {displayedDevices.map((client) => (
                  <div
                    key={client.id}
                    className="p-4 cursor-pointer hover:bg-muted/50 transition-colors"
                    onClick={() => navigate(`/device/${client.id}/info`)}
                  >
                    <div className="flex items-start gap-3">
                      <div className="h-11 w-11 rounded-xl bg-primary/10 flex items-center justify-center shrink-0">
                        <Smartphone className="h-5 w-5 text-primary" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="font-medium truncate">{client.deviceModel || 'Unknown'}</p>
                          {client.online ? (
                            <Badge className="bg-success text-white border-0 text-[10px] px-1.5 py-0">Online</Badge>
                          ) : (
                            <Badge variant="secondary" className="text-[10px] px-1.5 py-0">Offline</Badge>
                          )}
                        </div>
                        <p className="text-xs text-muted-foreground mt-0.5">{client.deviceBrand || ''} {client.deviceVersion || ''}</p>
                        <div className="flex items-center gap-3 mt-2 text-xs text-muted-foreground">
                          <span className="flex items-center gap-1">
                            <MapPin className="h-3 w-3" />
                            {getCountryFlag(client.country)} {client.city || client.country || 'Unknown'}
                          </span>
                          <span className="flex items-center gap-1">
                            <Clock className="h-3 w-3" />
                            {formatDate(client.lastSeen)}
                          </span>
                        </div>
                      </div>
                      <ChevronRight className="h-5 w-5 text-muted-foreground shrink-0" />
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {assignDialog && (
        <div className="fixed inset-0 z-50 bg-black/50 flex items-center justify-center p-4" onClick={() => setAssignDialog(null)}>
          <div className="bg-card rounded-xl border shadow-lg max-w-sm w-full p-6 space-y-4" onClick={e => e.stopPropagation()}>
            <div className="flex items-center gap-2">
              <UserPlus className="h-5 w-5 text-primary" />
              <h3 className="font-semibold">Assign Device</h3>
            </div>
            <p className="text-sm text-muted-foreground">Select a user to assign this device to. They will be the only one who can see and manage it.</p>
            {assignError && (
              <div className="p-2.5 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-xs">{assignError}</div>
            )}
            <select
              value={selectedUserId}
              onChange={(e) => setSelectedUserId(e.target.value)}
              className="w-full px-3 py-2 rounded-lg border bg-background text-sm"
            >
              <option value="">Select a user...</option>
              {users.map(u => (
                <option key={u.id} value={u.id}>{u.username} ({u.email})</option>
              ))}
            </select>
            <div className="flex gap-2">
              <Button variant="outline" className="flex-1" onClick={() => setAssignDialog(null)}>Cancel</Button>
              <Button className="flex-1" onClick={handleAssign} disabled={!selectedUserId || assigning}>
                {assigning ? 'Assigning...' : 'Assign'}
              </Button>
            </div>
          </div>
        </div>
      )}
      {confirmDialog}
    </div>
  );
}
