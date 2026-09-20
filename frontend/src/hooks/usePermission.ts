import { useAuthStore } from '@/store/auth';
import type { Permission } from '@/types';

export function useCan(permission: Permission): boolean {
  const hasPermission = useAuthStore(state => state.hasPermission);
  return hasPermission(permission);
}
