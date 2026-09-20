import { useState, useEffect, useRef } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from './sidebar';
import Header from './header';
import MobileNav from './mobile-nav';
import { useDevicesStore } from '@/store/devices';
import { initAdminSocket, disconnectAdminSocket } from '@/services/socket';

export default function AppLayout() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const fetchDashboard = useDevicesStore((s) => s.fetchDashboard);

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  const fetchDashboardRef = useRef(fetchDashboard);
  useEffect(() => {
    fetchDashboardRef.current = fetchDashboard;
  });

  useEffect(() => {
    let debounceTimer: ReturnType<typeof setTimeout> | null = null;
    const handleDeviceChange = () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        fetchDashboardRef.current();
      }, 2000);
    };
    initAdminSocket(handleDeviceChange);
    return () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      disconnectAdminSocket();
    };

  }, []);

  return (
    <div className="h-screen overflow-hidden flex">
      <aside className="hidden lg:flex lg:shrink-0">
        <Sidebar />
      </aside>

      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <Header onMobileMenuOpen={() => setMobileOpen(true)} />

        <main className="flex-1 overflow-y-auto overflow-x-hidden">
          <div className="p-4 md:p-6 lg:p-8">
            <Outlet />
          </div>
        </main>

        <footer className="shrink-0 border-t border-border bg-background px-4 md:px-6 lg:px-8 py-3">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <img src="/logo.svg" alt="Fason" className="h-5 shrink-0" />
            <a href="https://github.com/fahimahamed1" target="_blank" rel="noopener noreferrer" className="footer-credit">Made by <span className="footer-name">Fahim Ahamed</span></a>
          </div>
        </footer>
      </div>

      <MobileNav open={mobileOpen} onClose={() => setMobileOpen(false)} />
    </div>
  );
}
