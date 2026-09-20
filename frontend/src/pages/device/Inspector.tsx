import { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Label } from '@/components/ui/label';
import {
  Search, RefreshCw, Settings2, ChevronDown, ChevronRight,
  MousePointerClick, Square, CornerDownRight, Globe, Radio, X, Type,
} from 'lucide-react';
import { onInspectorUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard, EmptyState,
} from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import type { DeviceOutletContext } from '@/types';
import type { Socket } from 'socket.io-client';

interface NodeMetadata {
  role?: string;
  roleDescription?: string;
  resourceId?: string;
  text?: string;
  content?: string;
  hint?: string;
  tooltip?: string;
  paneTitle?: string;
  title?: string;
  x1?: number; y1?: number; x2?: number; y2?: number;
  scaledWidth?: string | number;
  scaledHeight?: string | number;
  dpScaleFactor?: string | number;
  heading?: boolean;
  checkable?: string;
  stateDescription?: string;
  selected?: boolean;
  contentInvalid?: boolean;
  errorMessage?: string;
  visibility?: string;
  importantForAccessibility?: boolean;
  properties?: string[];
  actions?: string[];
  collectionInfo?: string;
  collectionItemInfo?: string;
  windowId?: number;
  labeledBy?: string;
  labeledById?: number;
  labelForId?: number;
  links?: string[];
  locales?: string[];
}

interface A11yNode {
  id: number;
  name: string;
  metadata: NodeMetadata;
  children?: A11yNode[];
}

interface A11yTree {
  children?: A11yNode[];
}

interface Announcement {
  time: string;
  message: string;
}

function flattenTree(tree: A11yTree | null): A11yNode[] {
  const out: A11yNode[] = [];
  function walk(nodes?: A11yNode[]) {
    if (!nodes) return;
    for (const n of nodes) {
      out.push(n);
      if (n.children) walk(n.children);
    }
  }
  if (tree?.children) walk(tree.children);
  return out;
}

function findNodeById(tree: A11yTree | null, id: number): A11yNode | null {
  function walk(nodes?: A11yNode[]): A11yNode | null {
    if (!nodes) return null;
    for (const n of nodes) {
      if (n.id === id) return n;
      const found = walk(n.children);
      if (found) return found;
    }
    return null;
  }
  return walk(tree?.children);
}

function nodeArea(n: A11yNode): number {
  const w = (n.metadata.x2 ?? 0) - (n.metadata.x1 ?? 0);
  const h = (n.metadata.y2 ?? 0) - (n.metadata.y1 ?? 0);
  return w * h;
}

function transformVal(v: unknown): string {
  if (v === undefined || v === null) return '-';
  if (Array.isArray(v)) return v.join(', ');
  if (v === 'not checked') return 'false';
  if (v === 'checked') return 'true';
  return String(v);
}

function nodeKey(n: A11yNode): string {
  if (n.metadata.resourceId) return `rid:${n.metadata.resourceId}`;
  return `b:${n.metadata.x1 ?? 0},${n.metadata.y1 ?? 0},${n.metadata.x2 ?? 0},${n.metadata.y2 ?? 0}:${n.metadata.role ?? n.name}`;
}

function parseDp(v: string | number | undefined): number {
  if (v === undefined) return 999;
  const n = typeof v === 'number' ? v : parseFloat(v);
  return isNaN(n) ? 999 : n;
}

export default function InspectorPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tree, setTree] = useState<A11yTree | null>(null);
  const [screenshot, setScreenshot] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [hoveredId, setHoveredId] = useState<number | null>(null);
  const [capturing, setCapturing] = useState(false);
  const [includeAll, setIncludeAll] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [accessibilityEnabled, setAccessibilityEnabled] = useState<boolean | null>(null);
  const [accessibilityConnected, setAccessibilityConnected] = useState<boolean | null>(null);
  const [announcements, setAnnouncements] = useState<Announcement[]>([]);
  const [showAnnouncements, setShowAnnouncements] = useState(false);
  const [liveMode, setLiveMode] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(new Set());
  const [setTextDialog, setSetTextDialog] = useState<{ nodeId: number } | null>(null);
  const [setTextValue, setSetTextValue] = useState('');

  const [treeWidth, setTreeWidth] = useState(380);
  const [detailsWidth, setDetailsWidth] = useState(320);

  const [dragging, setDragging] = useState<{ type: 'tree' | 'details'; startX: number; startW: number } | null>(null);

  const containerEls = useRef<HTMLDivElement[]>([]);
  const setContainerRef = useCallback((el: HTMLDivElement | null) => {
    if (el) {
      if (!containerEls.current.includes(el)) containerEls.current.push(el);
    } else {
      containerEls.current = [];
    }
  }, []);
  const getVisibleContainer = useCallback((): HTMLDivElement | null => {
    return containerEls.current.find(el => el.clientWidth > 0 && el.clientHeight > 0) || null;
  }, []);

  const treeEls = useRef<HTMLDivElement[]>([]);
  const setTreeContainerRef = useCallback((el: HTMLDivElement | null) => {
    if (el) {
      if (!treeEls.current.includes(el)) treeEls.current.push(el);
    } else {
      treeEls.current = [];
    }
  }, []);
  const getVisibleTreeContainer = useCallback((): HTMLDivElement | null => {
    return treeEls.current.find(el => el.clientWidth > 0) || null;
  }, []);

  const [imgDims, setImgDims] = useState({ w: 0, h: 0 });
  const [containerDims, setContainerDims] = useState({ w: 0, h: 0 });
  const capturingTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const liveTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [socketConnected, setSocketConnected] = useState<boolean>(!!getAdminSocket()?.connected);

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
  const connected = socketConnected;

  const onSplitterMouseDown = useCallback((type: 'tree' | 'details', e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    const startW = type === 'tree' ? treeWidth : detailsWidth;
    setDragging({ type, startX: e.clientX, startW });
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, [treeWidth, detailsWidth]);

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      if (!dragging) return;
      const dx = e.clientX - dragging.startX;
      if (dragging.type === 'tree') {
        setTreeWidth(Math.max(250, Math.min(600, dragging.startW + dx)));
      } else {

        setDetailsWidth(Math.max(200, Math.min(700, dragging.startW - dx)));
      }
    };
    const onUp = () => {
      setDragging(null);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [dragging]);

  useEffect(() => {
    if (!screenshot) { setImgDims({ w: 0, h: 0 }); return; }
    let cancelled = false;
    const img = new Image();
    img.onload = () => { if (!cancelled) setImgDims({ w: img.width, h: img.height }); };
    img.src = `data:image/jpeg;base64,${screenshot}`;
    return () => { cancelled = true; img.onload = null; };
  }, [screenshot]);

  useEffect(() => {

    const ro = new ResizeObserver(() => {
      const visible = getVisibleContainer();
      if (visible) {
        setContainerDims({ w: visible.clientWidth, h: visible.clientHeight });
      }
    });
    containerEls.current.forEach(el => ro.observe(el));

    const visible = getVisibleContainer();
    if (visible) {
      setContainerDims({ w: visible.clientWidth, h: visible.clientHeight });
    }
    return () => ro.disconnect();
  }, [getVisibleContainer]);

  useEffect(() => {
    setTree(null);
    setScreenshot(null);
    setSelectedId(null);
    setHoveredId(null);
    setError(null);
    setAnnouncements([]);
    setAccessibilityEnabled(null);
    setAccessibilityConnected(null);
    setExpandedKeys(new Set());
  }, [id]);

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then(() => setLoading(false))
      .catch((err: any) => {
        setError(err?.response?.status === 404 ? 'Device not found' : 'Failed to load device');
        setLoading(false);
      });
  }, [id]);

  const resetCapturingAfter = useCallback((ms: number) => {
    if (capturingTimeoutRef.current) clearTimeout(capturingTimeoutRef.current);
    capturingTimeoutRef.current = setTimeout(() => {
      setCapturing(false);
      setError(prev => prev ?? 'Capture timed out. Try again.');
    }, ms);
  }, []);

  useEffect(() => {
    if (!id) return;
    const unsub = onInspectorUpdate((data: any) => {
      if (!data || data.id !== id) return;

      if (data.type === 'tree' && data.tree) {
        setTree(data.tree);

        setSelectedId(prev => prev !== null && findNodeById(data.tree, prev) ? prev : null);
        setHoveredId(prev => prev !== null && findNodeById(data.tree, prev) ? prev : null);
        setCapturing(false);
        setError(null);
        if (capturingTimeoutRef.current) { clearTimeout(capturingTimeoutRef.current); capturingTimeoutRef.current = null; }
      } else if (data.type === 'screenshot' && data.screenshot) {
        setScreenshot(data.screenshot);
      } else if (data.type === 'announcement' && data.announcement) {
        const now = new Date();
        const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;
        setAnnouncements((prev) => [...prev.slice(-49), { time, message: String(data.announcement) }]);
      } else if (data.type === 'error' || data.type === 'action_error') {
        setError(data.error || 'Inspector error');
        setCapturing(false);
        if (capturingTimeoutRef.current) { clearTimeout(capturingTimeoutRef.current); capturingTimeoutRef.current = null; }
      } else if (data.type === 'action_result') {

        if (data.success === false) {
          setError(`Action failed: ${data.action || 'unknown action'}`);
        }
      } else if (data.type === 'status') {
        setAccessibilityEnabled(data.accessibilityEnabled === true);
        setAccessibilityConnected(data.accessibilityConnected === true);
      }
    });
    return unsub;
  }, [id]);

  useEffect(() => {
    if (!id) return;
    clientsApi.sendCommand(id, CMD.INSPECTOR, { action: 'status' }).catch(() => {});
  }, [id]);

  const captureTree = useCallback(async () => {
    if (!id) return;
    setError(null);
    setCapturing(true);
    if (!liveMode) {
      setTree(null);
      setScreenshot(null);
    }
    resetCapturingAfter(20000);
    try {
      await clientsApi.sendCommand(id, CMD.INSPECTOR, { action: 'capture_tree', includeAll });
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to capture tree');
      setCapturing(false);
    }
  }, [id, includeAll, resetCapturingAfter, liveMode]);

  const enableAccessibility = useCallback(async () => {
    if (!id) return;
    try { await clientsApi.sendCommand(id, CMD.INSPECTOR, { action: 'open_settings' }); }
    catch (err: any) { setError(err?.response?.data?.error || 'Failed to open settings'); }
  }, [id]);

  const performNodeAction = useCallback(async (nodeId: number, actionId: number, _name: string) => {
    if (!id) return;
    try {
      await clientsApi.sendCommand(id, CMD.INSPECTOR, { action: 'node_action', nodeId, nodeAction: actionId });
    } catch (err: any) {
      setError(err?.response?.data?.error || `Failed to perform action on node ${nodeId}`);
    }
  }, [id]);

  const performSetText = useCallback(async (nodeId: number, text: string) => {
    if (!id) return;
    try {
      await clientsApi.sendCommand(id, CMD.INSPECTOR, { action: 'node_action', nodeId, nodeAction: 0x00200000, text });

      setSetTextDialog(null);
      setSetTextValue('');
    } catch (err: any) {
      setError(err?.response?.data?.error || `Failed to set text on node ${nodeId}`);

    }
  }, [id]);

  useEffect(() => {
    if (liveMode && connected) {
      captureTree();
      liveTimerRef.current = setInterval(captureTree, 5000);
    } else {
      if (liveTimerRef.current) { clearInterval(liveTimerRef.current); liveTimerRef.current = null; }
    }
    return () => {
      if (liveTimerRef.current) { clearInterval(liveTimerRef.current); liveTimerRef.current = null; }
    };
  }, [liveMode, connected, captureTree]);

  useEffect(() => {
    return () => {
      if (capturingTimeoutRef.current) clearTimeout(capturingTimeoutRef.current);
      if (liveTimerRef.current) clearInterval(liveTimerRef.current);
    };
  }, []);

  const getDisplayRect = useCallback(() => {
    if (!imgDims.w || !containerDims.w) return null;
    const imgAspect = imgDims.w / imgDims.h;
    const containerAspect = containerDims.w / containerDims.h;
    let dispW: number, dispH: number, offsetX: number, offsetY: number;
    if (imgAspect > containerAspect) {
      dispW = containerDims.w;
      dispH = containerDims.w / imgAspect;
      offsetX = 0;
      offsetY = (containerDims.h - dispH) / 2;
    } else {
      dispH = containerDims.h;
      dispW = containerDims.h * imgAspect;
      offsetX = (containerDims.w - dispW) / 2;
      offsetY = 0;
    }
    return { dispW, dispH, offsetX, offsetY, scaleX: dispW / imgDims.w, scaleY: dispH / imgDims.h };
  }, [imgDims, containerDims]);

  const flatTree = useMemo(() => tree ? flattenTree(tree) : [], [tree]);

  const handleScreenshotMove = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (!tree || !imgDims.w || flatTree.length === 0) return;
    const dr = getDisplayRect();
    if (!dr) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const px = (mx - dr.offsetX) / dr.scaleX;
    const py = (my - dr.offsetY) / dr.scaleY;

    const overlapping = flatTree.filter((n) => {
      const x1 = n.metadata.x1 ?? 0, y1 = n.metadata.y1 ?? 0;
      const x2 = n.metadata.x2 ?? 0, y2 = n.metadata.y2 ?? 0;
      return px >= x1 && px <= x2 && py >= y1 && py <= y2;
    });
    if (overlapping.length === 0) {
      setHoveredId(null);
      return;
    }
    overlapping.sort((a, b) => nodeArea(a) - nodeArea(b));
    setHoveredId(overlapping[0].id);
  }, [tree, flatTree, imgDims, getDisplayRect]);

  const handleScreenshotClick = useCallback(() => {
    if (hoveredId !== null) setSelectedId(hoveredId);
  }, [hoveredId]);

  const searchResults = useMemo(() => {
    if (!searchTerm.trim() || !tree) return new Set<number>();
    const term = searchTerm.toLowerCase();
    const results = new Set<number>();

    for (const n of flatTree) {
      const m = n.metadata;
      const haystack = [m.text, m.content, m.resourceId, m.role, m.paneTitle, m.title, m.tooltip, m.hint].filter(Boolean).join(' ').toLowerCase();
      if (haystack.includes(term)) results.add(n.id);
    }
    return results;
  }, [searchTerm, tree, flatTree]);

  const smallTargetNodes = useMemo(() => {
    if (!tree) return new Set<number>();
    const results = new Set<number>();

    for (const n of flatTree) {
      if (n.metadata.properties?.includes('clickable') && n.metadata.visibility !== 'invisible') {
        const w = parseDp(n.metadata.scaledWidth);
        const h = parseDp(n.metadata.scaledHeight);
        if (w < 24 || h < 24) results.add(n.id);
      }
    }
    return results;
  }, [tree, flatTree]);

  useEffect(() => {
    if (selectedId === null) return;

    const container = getVisibleTreeContainer();
    if (!container) return;
    const el = container.querySelector(`[data-node-id="${selectedId}"]`) as HTMLElement | null;
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [selectedId, getVisibleTreeContainer]);

  const toggleExpand = useCallback((key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const exportTreeData = useMemo(() => {

    if (!tree) return [];
    return flatTree.map(n => ({
      id: n.id,
      name: n.name,
      role: n.metadata.role || '',
      text: n.metadata.text || '',
      resourceId: n.metadata.resourceId || '',
      bounds: `${n.metadata.x1 ?? 0},${n.metadata.y1 ?? 0},${n.metadata.x2 ?? 0},${n.metadata.y2 ?? 0}`,
      clickable: n.metadata.properties?.includes('clickable') ? 'yes' : 'no',
    }));
  }, [tree, flatTree]);

  const clearCapture = useCallback(() => {
    setTree(null);
    setScreenshot(null);
    setSelectedId(null);
    setHoveredId(null);
  }, []);

  const dataActions = buildDataActions({
    data: exportTreeData,
    exportPrefix: 'inspector-tree',
    onClear: clearCapture,
  });

  const selectedNode = useMemo(() => selectedId !== null ? findNodeById(tree, selectedId) : null, [tree, selectedId]);
  const hoveredNode = useMemo(() => hoveredId !== null ? findNodeById(tree, hoveredId) : null, [tree, hoveredId]);

  if (loading) {
    return <LoadingSkeleton rows={6} />;
  }

  const canAct = online && connected && !capturing;

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="Accessibility Inspector"
        subtitle="Capture and explore the device's accessibility node tree"
        actions={[
          { label: capturing ? 'Capturing…' : 'Capture Tree', icon: RefreshCw, onClick: captureTree, disabled: !canAct },
          { label: 'All Views', icon: Square, onClick: () => setIncludeAll(!includeAll), variant: includeAll ? 'default' : 'outline' },

          { label: 'Live', icon: Radio, onClick: () => setLiveMode(!liveMode), disabled: !connected && !liveMode, variant: liveMode ? 'default' : 'outline' },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={!tree} />}
        refresh={captureTree}
        loading={capturing}
      />

      {}
      <div className="flex items-center gap-2 flex-wrap">
        {accessibilityEnabled === false && (
          <StatusBadge label="A11y Off" status="danger" />
        )}
        {accessibilityEnabled === true && accessibilityConnected === false && (
          <StatusBadge label="A11y Not Ready" status="warning" />
        )}
        {accessibilityEnabled === true && accessibilityConnected === true && (
          <StatusBadge label="A11y On" status="success" />
        )}
        {smallTargetNodes.size > 0 && (
          <StatusBadge label={`${smallTargetNodes.size} small targets`} status="danger" />
        )}
        {!online && <StatusBadge label="Device Offline" status="danger" />}
        {announcements.length > 0 && (
          <Button variant={showAnnouncements ? 'default' : 'outline'} size="sm" onClick={() => setShowAnnouncements(!showAnnouncements)} className="gap-1 text-xs h-7">
            <CornerDownRight className="h-3 w-3" /> {announcements.length} Announcements
          </Button>
        )}
      </div>

      {error && <ErrorAlert message={error} onRetry={captureTree} />}

      {}
      <div className="flex flex-wrap gap-2 items-center">
        {accessibilityEnabled === false && (
          <Button variant="outline" onClick={enableAccessibility} disabled={!online} className="gap-2 text-amber-600" title="Open Accessibility Settings on device">
            <Settings2 className="h-4 w-4" /> Enable A11y
          </Button>
        )}
        <div className="relative flex-1 min-w-[200px] max-w-md">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search nodes…"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="pl-8"
          />
        </div>
      </div>

      {}
      {showAnnouncements && announcements.length > 0 && (
        <SectionCard title="Accessibility Announcements" icon={CornerDownRight}>
          <div className="max-h-48 overflow-y-auto">
            <div className="space-y-1">
              {[...announcements].reverse().map((a, i) => (
                <div key={i} className="flex gap-3 text-xs py-1 border-b last:border-0">
                  <span className="text-muted-foreground font-mono shrink-0">{a.time}</span>
                  <span>{a.message}</span>
                </div>
              ))}
            </div>
          </div>
        </SectionCard>
      )}

      {}
      {setTextDialog && (
        <Card className="shadow-none">
          <CardHeader className="pb-2 flex flex-row items-center justify-between">
            <CardTitle className="text-sm flex items-center gap-2"><Type className="h-4 w-4" /> Set Text</CardTitle>
            <Button variant="ghost" size="sm" onClick={() => { setSetTextDialog(null); setSetTextValue(''); }} aria-label="Close dialog"><X className="h-4 w-4" /></Button>
          </CardHeader>
          <CardContent className="space-y-2">
            <Input
              placeholder="Enter text to set…"
              value={setTextValue}
              onChange={(e) => setSetTextValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && setTextValue) performSetText(setTextDialog.nodeId, setTextValue);
                if (e.key === 'Escape') { setSetTextDialog(null); setSetTextValue(''); }
              }}
              autoFocus
            />
            <div className="flex gap-2 justify-end">
              <Button variant="outline" size="sm" onClick={() => { setSetTextDialog(null); setSetTextValue(''); }}>Cancel</Button>
              <Button size="sm" disabled={!setTextValue} onClick={() => performSetText(setTextDialog.nodeId, setTextValue)}>Set Text</Button>
            </div>
          </CardContent>
        </Card>
      )}

      {}
      <div className="hidden lg:flex items-stretch gap-0" style={{ height: 'calc(70vh + 60px)' }}>
        {}
        <Card className="shadow-none overflow-hidden flex-1 min-w-0">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center gap-2">
              <MousePointerClick className="h-4 w-4" /> Screenshot
              {screenshot && <span className="text-xs text-muted-foreground font-normal">({imgDims.w}×{imgDims.h})</span>}
            </CardTitle>
          </CardHeader>
          <CardContent className="p-2 h-[calc(100%-44px)]">
            <div
              ref={setContainerRef}
              className="relative bg-black rounded-lg flex items-center justify-center overflow-hidden h-full"
              style={{ minHeight: '400px' }}
              onMouseMove={handleScreenshotMove}
              onClick={handleScreenshotClick}
            >
              {screenshot ? (
                <div className="relative w-full h-full">
                  <img
                    src={`data:image/jpeg;base64,${screenshot}`}
                    alt="Device screenshot"
                    className="max-w-full max-h-full object-contain mx-auto"
                    draggable={false}
                  />
                  {hoveredNode && (
                    <NodeOverlay node={hoveredNode} displayRect={getDisplayRect()} color="rgba(34, 197, 94, 0.4)" border="rgb(34, 197, 94)" />
                  )}
                  {selectedNode && (
                    <NodeOverlay node={selectedNode} displayRect={getDisplayRect()} color="rgba(239, 68, 68, 0.3)" border="rgb(239, 68, 68)" />
                  )}
                </div>
              ) : (
                <EmptyState
                  icon={Search}
                  title="No capture yet"
                  description='Click "Capture Tree" to begin'
                  action={{ label: 'Capture Tree', onClick: captureTree, disabled: !canAct }}
                />
              )}
            </div>
          </CardContent>
        </Card>

        {}
        <div
          className="w-1.5 cursor-col-resize bg-border hover:bg-primary/40 transition-colors shrink-0 relative group"
          onMouseDown={(e) => onSplitterMouseDown('tree', e)}
          title="Drag to resize"
        >
          <div className="absolute inset-y-0 -left-1 -right-1" />
        </div>

        {}
        <Card className="shadow-none overflow-hidden shrink-0" style={{ width: `${treeWidth}px` }}>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">Node Tree</CardTitle>
          </CardHeader>
          <CardContent className="p-2 overflow-y-auto h-[calc(100%-44px)]" ref={setTreeContainerRef}>
            {tree?.children ? (
              <div className="text-xs">
                {tree.children.map((node, i) => (
                  <TreeNode
                    key={node.id != null ? `id:${node.id}` : `idx:${i}:${node.name}`}
                    node={node}
                    depth={0}
                    selectedId={selectedId}
                    hoveredId={hoveredId}
                    searchResults={searchResults}
                    smallTargets={smallTargetNodes}
                    expandedKeys={expandedKeys}
                    onSelect={(nid) => setSelectedId(nid)}
                    onHover={(nid) => setHoveredId(nid)}
                    onToggleExpand={toggleExpand}
                  />
                ))}
              </div>
            ) : (
              <EmptyState
                icon={Search}
                title="No tree captured"
                description='Click "Capture Tree" to begin'
                action={{ label: 'Capture', onClick: captureTree, disabled: !canAct }}
              />
            )}
          </CardContent>
        </Card>

        {}
        <div
          className="w-1.5 cursor-col-resize bg-border hover:bg-primary/40 transition-colors shrink-0 relative group"
          onMouseDown={(e) => onSplitterMouseDown('details', e)}
          title="Drag to resize"
        >
          <div className="absolute inset-y-0 -left-1 -right-1" />
        </div>

        {}
        <Card className="shadow-none overflow-hidden shrink-0" style={{ width: `${detailsWidth}px` }}>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">Details</CardTitle>
          </CardHeader>
          <CardContent className="p-2 overflow-y-auto h-[calc(100%-44px)]">
            {selectedNode ? (
              <NodeDetails node={selectedNode} onAction={performNodeAction} onSetText={(nodeId) => { setSetTextDialog({ nodeId }); setSetTextValue(''); }} />
            ) : (
              <EmptyState
                icon={MousePointerClick}
                title="Select a node"
                description="Click the screenshot or tree"
              />
            )}
          </CardContent>
        </Card>
      </div>

      {}
      <div className="lg:hidden space-y-4">
        <Card className="shadow-none overflow-hidden">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm flex items-center gap-2">
              <MousePointerClick className="h-4 w-4" /> Screenshot
            </CardTitle>
          </CardHeader>
          <CardContent className="p-2">
            <div
              ref={setContainerRef}
              className="relative bg-black rounded-lg flex items-center justify-center overflow-hidden"
              style={{ minHeight: '400px', maxHeight: '70vh' }}
              onMouseMove={handleScreenshotMove}
              onClick={handleScreenshotClick}
            >
              {screenshot ? (
                <div className="relative w-full h-full">
                  <img src={`data:image/jpeg;base64,${screenshot}`} alt="Device screenshot" className="max-w-full max-h-[68vh] object-contain mx-auto" draggable={false} />
                  {hoveredNode && <NodeOverlay node={hoveredNode} displayRect={getDisplayRect()} color="rgba(34, 197, 94, 0.4)" border="rgb(34, 197, 94)" />}
                  {selectedNode && <NodeOverlay node={selectedNode} displayRect={getDisplayRect()} color="rgba(239, 68, 68, 0.3)" border="rgb(239, 68, 68)" />}
                </div>
              ) : (
                <EmptyState
                  icon={Search}
                  title="No capture yet"
                  description='Click "Capture Tree" to begin'
                  action={{ label: 'Capture', onClick: captureTree, disabled: !canAct }}
                />
              )}
            </div>
          </CardContent>
        </Card>

        <Card className="shadow-none overflow-hidden">
          <CardHeader className="pb-2"><CardTitle className="text-sm">Node Tree</CardTitle></CardHeader>
          <CardContent className="p-2 max-h-[60vh] overflow-y-auto" ref={setTreeContainerRef}>
            {tree?.children ? (
              <div className="text-xs">
                {tree.children.map((node, i) => (
                  <TreeNode key={node.id != null ? `id:${node.id}` : `idx:${i}:${node.name}`} node={node} depth={0} selectedId={selectedId} hoveredId={hoveredId} searchResults={searchResults} smallTargets={smallTargetNodes} expandedKeys={expandedKeys} onSelect={(nid) => setSelectedId(nid)} onHover={(nid) => setHoveredId(nid)} onToggleExpand={toggleExpand} />
                ))}
              </div>
            ) : (
              <EmptyState icon={Search} title="No tree captured" description='Click "Capture Tree" to begin' action={{ label: 'Capture', onClick: captureTree, disabled: !canAct }} />
            )}
          </CardContent>
        </Card>

        <Card className="shadow-none overflow-hidden">
          <CardHeader className="pb-2"><CardTitle className="text-sm">Details</CardTitle></CardHeader>
          <CardContent className="p-2 max-h-[60vh] overflow-y-auto">
            {selectedNode ? (
              <NodeDetails node={selectedNode} onAction={performNodeAction} onSetText={(nodeId) => { setSetTextDialog({ nodeId }); setSetTextValue(''); }} />
            ) : (
              <EmptyState icon={MousePointerClick} title="Select a node" description="Click the screenshot or tree" />
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function NodeOverlay({ node, displayRect, color, border }: {
  node: A11yNode;
  displayRect: { dispW: number; dispH: number; offsetX: number; offsetY: number; scaleX: number; scaleY: number } | null;
  color: string;
  border: string;
}) {
  if (!displayRect) return null;
  const x = (node.metadata.x1 ?? 0) * displayRect.scaleX + displayRect.offsetX;
  const y = (node.metadata.y1 ?? 0) * displayRect.scaleY + displayRect.offsetY;
  const w = ((node.metadata.x2 ?? 0) - (node.metadata.x1 ?? 0)) * displayRect.scaleX;
  const h = ((node.metadata.y2 ?? 0) - (node.metadata.y1 ?? 0)) * displayRect.scaleY;
  return (
    <div
      className="absolute pointer-events-none rounded"
      style={{
        left: `${x}px`,
        top: `${y}px`,
        width: `${Math.max(w, 2)}px`,
        height: `${Math.max(h, 2)}px`,
        backgroundColor: color,
        border: `2px solid ${border}`,
      }}
    />
  );
}

function TreeNode({ node, depth, selectedId, hoveredId, searchResults, smallTargets, expandedKeys, onSelect, onHover, onToggleExpand }: {
  node: A11yNode;
  depth: number;
  selectedId: number | null;
  hoveredId: number | null;
  searchResults: Set<number>;
  smallTargets: Set<number>;
  expandedKeys: Set<string>;
  onSelect: (id: number) => void;
  onHover: (id: number | null) => void;
  onToggleExpand: (key: string) => void;
}) {
  const key = nodeKey(node);

  const isExpanded = depth < 2 ? true : expandedKeys.has(key);
  const hasChildren = node.children && node.children.length > 0;
  const isSelected = selectedId === node.id;
  const isHovered = hoveredId === node.id;
  const isMatch = searchResults.has(node.id);
  const isSmallTarget = smallTargets.has(node.id);
  const m = node.metadata;

  const label = m.text || m.content || m.paneTitle || m.title || m.role || node.name;

  return (
    <div>
      <div
        data-node-id={node.id}
        className={`flex items-center gap-1 py-0.5 px-1 rounded cursor-pointer transition-colors ${
          isSelected ? 'bg-primary/20' : isHovered ? 'bg-muted' : 'hover:bg-muted/50'
        } ${isMatch ? 'ring-1 ring-yellow-400' : ''}`}
        style={{ paddingLeft: `${depth * 12 + 4}px` }}
        onClick={() => onSelect(node.id)}
        onMouseEnter={() => onHover(node.id)}
        onMouseLeave={() => onHover(null)}
      >
        {hasChildren ? (
          <button
            onClick={(e) => { e.stopPropagation(); onToggleExpand(key); }}
            className="shrink-0 p-0 hover:bg-muted rounded"
          >
            {isExpanded ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          </button>
        ) : (
          <span className="w-3 shrink-0" />
        )}
        <span className={`truncate ${m.visibility === 'invisible' ? 'opacity-40 line-through' : ''} ${!m.importantForAccessibility ? 'opacity-60' : ''}`}>
          <span className="font-medium">{node.name}</span>
          {label && label !== node.name && (
            <span className="text-muted-foreground ml-1">{label}</span>
          )}
        </span>
        {m.properties?.includes('clickable') && (
          <Badge variant="secondary" className="text-[10px] px-1 py-0 h-3.5 shrink-0">click</Badge>
        )}
        {m.heading && (
          <Badge className="text-[10px] px-1 py-0 h-3.5 shrink-0 bg-blue-500/15 text-blue-600 border-blue-500/30">H</Badge>
        )}
        {isSmallTarget && (
          <Badge variant="destructive" className="text-[10px] px-1 py-0 h-3.5 shrink-0" title="Clickable node < 24dp. Accessibility issue.">
            <MousePointerClick className="h-2 w-2" />
          </Badge>
        )}
      </div>
      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child, i) => (
            <TreeNode
              key={child.id ?? i}
              node={child}
              depth={depth + 1}
              selectedId={selectedId}
              hoveredId={hoveredId}
              searchResults={searchResults}
              smallTargets={smallTargets}
              expandedKeys={expandedKeys}
              onSelect={onSelect}
              onHover={onHover}
              onToggleExpand={onToggleExpand}
            />
          ))}
        </div>
      )}
    </div>
  );
}

const ACTION_IDS: { id: number; name: string; needsText?: boolean }[] = [
  { id: 0x00000010, name: 'Click' },
  { id: 0x00000020, name: 'Long Click' },
  { id: 0x00000001, name: 'Focus' },
  { id: 0x00000002, name: 'Clear Focus' },
  { id: 0x00000004, name: 'Select' },
  { id: 0x00000008, name: 'Clear Selection' },
  { id: 0x00001000, name: 'Scroll Forward' },
  { id: 0x00002000, name: 'Scroll Backward' },
  { id: 0x00400000, name: 'Scroll Up' },
  { id: 0x00800000, name: 'Scroll Down' },
  { id: 0x01000000, name: 'Scroll Left' },
  { id: 0x02000000, name: 'Scroll Right' },
  { id: 0x00200000, name: 'Set Text', needsText: true },
  { id: 0x00080000, name: 'Collapse' },
  { id: 0x00040000, name: 'Expand' },
  { id: 0x00000400, name: 'Dismiss' },
];

function NodeDetails({ node, onAction, onSetText }: { node: A11yNode; onAction: (nodeId: number, actionId: number, name: string) => void; onSetText: (nodeId: number) => void }) {
  const m = node.metadata;
  const widthPx = (m.x2 ?? 0) - (m.x1 ?? 0);
  const heightPx = (m.y2 ?? 0) - (m.y1 ?? 0);
  const isWebView = m.role?.toLowerCase().includes('webview') || node.name.toLowerCase().includes('webview');
  const scaledW = parseDp(m.scaledWidth);
  const scaledH = parseDp(m.scaledHeight);
  const isSmallTarget = m.properties?.includes('clickable') && m.visibility !== 'invisible' && (scaledW < 24 || scaledH < 24);

  const rows: { label: string; value: string }[] = [
    { label: 'resource id', value: transformVal(m.resourceId) },
    { label: 'role', value: transformVal(m.role) },
    { label: 'role desc', value: transformVal(m.roleDescription) },
    { label: 'text', value: transformVal(m.text) },
    { label: 'content desc', value: transformVal(m.content) },
    { label: 'hint', value: transformVal(m.hint) },
    { label: 'tooltip', value: transformVal(m.tooltip) },
    { label: 'pane title', value: transformVal(m.paneTitle) },
    { label: 'labeled by', value: transformVal(m.labeledBy) },
    { label: 'label for id', value: transformVal(m.labelForId) },
    { label: 'links', value: transformVal(m.links) },
    { label: 'locales', value: transformVal(m.locales) },
    { label: 'heading', value: m.heading ? 'true' : 'false' },
    { label: 'state', value: transformVal(m.checkable) },
    { label: 'state desc', value: transformVal(m.stateDescription) },
    { label: 'selected', value: m.selected ? 'true' : 'false' },
    { label: 'content invalid', value: m.contentInvalid ? 'true' : 'false' },
    { label: 'error message', value: transformVal(m.errorMessage) },
    { label: 'visibility', value: transformVal(m.visibility) },
    { label: 'important', value: m.importantForAccessibility ? 'true' : 'false' },
    { label: 'properties', value: transformVal(m.properties) },
    { label: 'actions', value: transformVal(m.actions) },
    { label: 'collection', value: transformVal(m.collectionInfo) },
    { label: 'collection item', value: transformVal(m.collectionItemInfo) },
    { label: 'actual size', value: `${widthPx} px × ${heightPx} px` },
    { label: 'scaled size', value: `${m.scaledWidth ?? '-'} dp × ${m.scaledHeight ?? '-'} dp` },
    { label: 'dp scale', value: transformVal(m.dpScaleFactor) },
    { label: 'bounds', value: `(${m.x1 ?? 0}, ${m.y1 ?? 0}) → (${m.x2 ?? 0}, ${m.y2 ?? 0})` },
  ];

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-center gap-2 flex-wrap">
        <span className="font-semibold text-sm">{node.name}</span>
        {m.properties?.includes('focused') && <Badge variant="secondary" className="text-[10px]">focused</Badge>}
        {m.properties?.includes('accessibility focused') && <Badge variant="secondary" className="text-[10px]">a11y focused</Badge>}
        {isWebView && <Badge className="text-[10px] gap-1 bg-success/15 text-success border-success/30"><Globe className="h-3 w-3" /> WebView</Badge>}
        {isSmallTarget && <Badge variant="destructive" className="text-[10px] gap-1" title="Clickable node < 24dp. Accessibility issue."><MousePointerClick className="h-3 w-3" /> Small Target</Badge>}
      </div>
      <div className="rounded-md border">
        <table className="w-full text-xs">
          <tbody>
            {rows.map((row, i) => (
              <tr key={i} className={i % 2 === 0 ? 'bg-muted/30' : ''}>
                <td className="py-1 px-2 font-medium text-muted-foreground whitespace-nowrap align-top w-1/3">{row.label}</td>
                <td className="py-1 px-2 font-mono break-words">{row.value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {}
      <div>
        <Label className="text-xs mb-2 block">Perform Action</Label>
        <div className="flex flex-wrap gap-1">
          {ACTION_IDS.map((a) => {
            const supported = m.actions?.some((act) => {
              const actLower = act.toLowerCase();
              const nameLower = a.name.toLowerCase().split(' ')[0];
              return actLower.includes(nameLower) || actLower.includes(a.name.toLowerCase());
            });
            if (!supported && a.id !== 0x00000010 && !a.needsText) return null;
            return (
              <Button
                key={a.id}
                variant="outline"
                size="sm"
                className="text-xs h-7 gap-1"
                onClick={() => a.needsText ? onSetText(node.id) : onAction(node.id, a.id, a.name)}
              >
                {a.needsText && <Type className="h-3 w-3" />}
                {a.name}
              </Button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
