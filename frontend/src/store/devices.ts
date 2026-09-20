import { create } from 'zustand';
import type { ClientDevice, DashboardData } from '@/types';
import { dashboardApi, clientsApi } from '@/services/api';
import { invalidatePageCache } from '@/hooks/useDeviceData';

interface DevicesState {
  onlineClients: ClientDevice[];
  offlineClients: ClientDevice[];
  stats: DashboardData['stats'] | null;
  selectedDevice: ClientDevice | null;
  isLoading: boolean;
  error: string | null;
  fetchDashboard: (silent?: boolean) => Promise<void>;
  fetchClients: (silent?: boolean) => Promise<void>;
  selectDevice: (device: ClientDevice | null) => void;
  deleteDevice: (id: string) => Promise<boolean>;
}

export const useDevicesStore = create<DevicesState>((set, get) => ({
  onlineClients: [],
  offlineClients: [],
  stats: null,
  selectedDevice: null,
  isLoading: false,
  error: null,

  fetchDashboard: async (silent = false) => {

    if (!silent) set({ isLoading: true, error: null });
    try {
      const res = await dashboardApi.getData();
      if (res.data.success) {
        const data = res.data.data;
        set({
          onlineClients: data.onlineClients,
          offlineClients: data.offlineClients,
          stats: data.stats,
          isLoading: false,
          error: null,
        });
      } else {
        set({ isLoading: false, error: res.data.error || 'Failed to load dashboard' });
      }
    } catch (err: unknown) {
      const msg = (err as any)?.response?.data?.error || (err instanceof Error ? err.message : 'Failed to load dashboard');
      set({ error: msg, isLoading: false });
    }
  },

  fetchClients: async (silent = false) => {
    if (!silent) set({ isLoading: true, error: null });
    try {
      const res = await clientsApi.getAll();
      if (res.data.success) {
        const data = res.data.data;
        const clients = Array.isArray(data?.clients) ? data.clients : [];
        const online = clients.filter((c: ClientDevice) => c.online);
        const offline = clients.filter((c: ClientDevice) => !c.online);
        set({
          onlineClients: online,
          offlineClients: offline,
          stats: clients.length > 0 || data.total != null ? {
            ...get().stats,
            totalClients: data.total ?? clients.length,
            onlineClients: data.online ?? online.length,
            offlineClients: data.offline ?? offline.length,
          } as DashboardData['stats'] : get().stats,
          isLoading: false,
          error: null,
        });
      } else {
        set({ isLoading: false, error: res.data.error || 'Failed to load clients' });
      }
    } catch (err: unknown) {
      const msg = (err as any)?.response?.data?.error || (err instanceof Error ? err.message : 'Failed to load clients');
      set({ error: msg, isLoading: false });
    }
  },

  selectDevice: (device) => set({ selectedDevice: device }),

  deleteDevice: async (id) => {
    try {
      const res = await clientsApi.delete(id);
      if (res.data.success) {
        set((state) => ({
          onlineClients: state.onlineClients.filter(c => c.id !== id),
          offlineClients: state.offlineClients.filter(c => c.id !== id),
          selectedDevice: state.selectedDevice?.id === id ? null : state.selectedDevice,
        }));

        invalidatePageCache(id);
        return true;
      }
      return false;
    } catch {
      return false;
    }
  },
}));
