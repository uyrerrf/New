package com.fason.app.features.unlock;

import android.accessibilityservice.GestureDescription;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class UnlockAccessibility {
    private static final String TAG = "UnlockA11y";
    private static final int TAP_DURATION_MS = 50;
    private static final int DIGIT_DELAY_MS = 150;
    private static final int ROOT_RETRY_COUNT = 5;
    private static final int ROOT_RETRY_DELAY_MS = 400;
    private final FasonAccessibilityService host;

    UnlockAccessibility(FasonAccessibilityService host) {
        this.host = host;
    }

    boolean attemptUnlock(String pin) {
        AccessibilityNodeInfo root = null;
        List<AccessibilityNodeInfo> obtained = new ArrayList<>();
        try {
            root = getRootWithRetry();
            if (root == null) {
                Log.w(TAG, "No root node");
                return false;
            }
            Map<Character, AccessibilityNodeInfo> digitButtons = new HashMap<>();
            collectDigitButtons(root, digitButtons, obtained);
            Log.i(TAG, "Found " + digitButtons.size() + " digit buttons");
            if (digitButtons.size() >= 10) {
                if (clickDigits(pin, digitButtons, root, obtained)) {
                    Log.i(TAG, "PIN entered");
                    return true;
                }
                Log.w(TAG, "Click failed, not cascading");
                return false;
            }
            AccessibilityNodeInfo pwdField = findPasswordField(root, obtained);
            if (pwdField != null) {
                Log.i(TAG, "Found password field");
                if (setTextField(pin, pwdField, root, obtained)) {
                    return true;
                }
                Log.w(TAG, "ACTION_SET_TEXT failed");
                return false;
            }
            Rect patternBounds = findLargeNonTextInteractiveArea(root);
            if (patternBounds != null && isLikelyPatternView(patternBounds)) {
                Log.i(TAG, "Pattern lock detected");
                return drawPattern(pin, patternBounds);
            }
            Log.w(TAG, "No unlock method found");
            return false;
        } finally {
            for (AccessibilityNodeInfo n : obtained) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            try { if (root != null) root.recycle(); } catch (Exception ignored) {}
        }
    }

    void swipeUp(int durationMs) {
        try {
            int w = host.getResources().getDisplayMetrics().widthPixels;
            int h = host.getResources().getDisplayMetrics().heightPixels;
            Path path = new Path();
            path.moveTo(w / 2f, h * 0.75f);
            path.lineTo(w / 2f, h * 0.25f);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
            GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
            host.dispatchGesture(gesture, null, null);
            Log.i(TAG, "Swipe up dispatched");
        } catch (Exception e) {
            Log.e(TAG, "Swipe up failed", e);
        }
    }
    private boolean clickDigits(String pin, Map<Character, AccessibilityNodeInfo> digitButtons,
                                 AccessibilityNodeInfo root, List<AccessibilityNodeInfo> tracker) {
        int digitsEntered = 0;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            AccessibilityNodeInfo btn = digitButtons.get(c);
            if (btn == null) {
                Log.w(TAG, "Digit '" + c + "' not found (entered " + digitsEntered + " so far)");
                return false;
            }
            if (!clickNodeOrParent(btn, tracker)) {
                Log.w(TAG, "Click failed on '" + c + "' (entered " + digitsEntered + " so far)");
                return false;
            }
            digitsEntered++;
            sleep(DIGIT_DELAY_MS);
        }
        sleep(300);
        AccessibilityNodeInfo submit = findSubmitButtonStructurally(root, tracker);
        if (submit != null) {
            clickNodeOrParent(submit, tracker);
            Log.i(TAG, "Clicked submit");
        }
        return true;
    }

    private boolean setTextField(String pin, AccessibilityNodeInfo field,
                                  AccessibilityNodeInfo root, List<AccessibilityNodeInfo> tracker) {
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            Log.w(TAG, "ACTION_SET_TEXT failed");
            return false;
        }
        sleep(300);
        AccessibilityNodeInfo submit = findSubmitButtonStructurally(root, tracker);
        if (submit != null) clickNodeOrParent(submit, tracker);
        return true;
    }

    private boolean drawPattern(String pattern, Rect bounds) {
        try {
            float cellW = (bounds.right - bounds.left) / 3f;
            float cellH = (bounds.bottom - bounds.top) / 3f;
            float[][] grid = new float[9][2];
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int idx = row * 3 + col;
                    grid[idx][0] = bounds.left + cellW * (col + 0.5f);
                    grid[idx][1] = bounds.top + cellH * (row + 0.5f);
                }
            }
            Path path = new Path();
            boolean first = true;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c < '1' || c > '9') continue;
                int node = c - '1';
                if (first) { path.moveTo(grid[node][0], grid[node][1]); first = false; }
                else { path.lineTo(grid[node][0], grid[node][1]); }
            }
            if (first) return false;
            long duration = Math.max(500, pattern.length() * 200L);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
            GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
            host.dispatchGesture(gesture, null, null);
            Log.i(TAG, "Pattern drawn (" + pattern.length() + " nodes)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pattern failed", e);
            return false;
        }
    }
    private AccessibilityNodeInfo findSubmitButtonStructurally(
            AccessibilityNodeInfo root, List<AccessibilityNodeInfo> tracker) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectClickableLeaves(root, candidates);
        List<AccessibilityNodeInfo> nonDigitCandidates = new ArrayList<>();
        for (AccessibilityNodeInfo c : candidates) {
            String label = getNodeLabel(c);
            boolean isDigit = label != null && label.length() == 1 &&
                              label.charAt(0) >= '0' && label.charAt(0) <= '9';
            if (!isDigit) {
                nonDigitCandidates.add(c);
            } else {
                try { c.recycle(); } catch (Exception ignored) {}
            }
        }
        if (nonDigitCandidates.isEmpty()) {
            Log.d(TAG, "No submit candidates");
            return null;
        }
        boolean isRtl = isRtlLayout();
        int tolerance = getPositionTolerancePx();
        Rect maxBounds = new Rect();
        maxBounds.left = Integer.MAX_VALUE;
        maxBounds.top = Integer.MAX_VALUE;
        maxBounds.right = Integer.MIN_VALUE;
        maxBounds.bottom = Integer.MIN_VALUE;
        Rect temp = new Rect();
        for (AccessibilityNodeInfo c : nonDigitCandidates) {
            c.getBoundsInScreen(temp);
            maxBounds.left = Math.min(maxBounds.left, temp.left);
            maxBounds.top = Math.min(maxBounds.top, temp.top);
            maxBounds.right = Math.max(maxBounds.right, temp.right);
            maxBounds.bottom = Math.max(maxBounds.bottom, temp.bottom);
        }
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nonDigitCandidates) {
            int score = 0;
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
                if (best != null) {
                    try { best.recycle(); } catch (Exception ignored) {}
                }
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        for (AccessibilityNodeInfo node : nonDigitCandidates) {
            if (node != best) {
                try { node.recycle(); } catch (Exception ignored) {}
            }
        }
        if (best != null && tracker != null) tracker.add(best);
        return best;
    }

    private void collectClickableLeaves(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        boolean hasClickableChild = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (child.isClickable()) hasClickableChild = true;
                collectClickableLeaves(child, out);
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
        if (node.isClickable() && node.isEnabled() && !hasClickableChild) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
    }

    private boolean isRtlLayout() {
        try {
            Configuration config = host.getResources().getConfiguration();
            return config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        } catch (Exception e) {
            return false;
        }
    }

    private int getPositionTolerancePx() {
        try {
            float density = host.getResources().getDisplayMetrics().density;
            return (int) (50 * density);
        } catch (Exception e) {
            return 50;
        }
    }

    private Rect findLargeNonTextInteractiveArea(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        int minArea = (int) (screenW * screenH * 0.15);
        List<AccessibilityNodeInfo> allNodes = new ArrayList<>();
        collectAllNodes(root, allNodes);
        Rect best = null;
        int bestArea = 0;
        for (AccessibilityNodeInfo node : allNodes) {
            if (node == null) continue;
            String label = getNodeLabel(node);
            boolean hasText = label != null && !label.isEmpty();
            boolean isClickable = node.isClickable();
            boolean isPassword = node.isPassword();
            if (hasText || isClickable || isPassword) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int area = bounds.width() * bounds.height();
            if (area > minArea && area > bestArea) {
                bestArea = area;
                best = bounds;
            }
        }
        for (AccessibilityNodeInfo n : allNodes) {
            try { n.recycle(); } catch (Exception ignored) {}
        }
        return best;
    }

    private boolean isLikelyPatternView(Rect bounds) {
        if (bounds == null) return false;
        float aspectRatio = (float) bounds.width() / (float) bounds.height();
        boolean isSquareish = aspectRatio > 0.5f && aspectRatio < 2.0f;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        float screenArea = screenW * screenH;
        float viewArea = bounds.width() * bounds.height();
        boolean isSignificant = viewArea > screenArea * 0.1f;
        return isSquareish && isSignificant;
    }

    private void collectAllNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllNodes(child, out);
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
    }
    private void collectDigitButtons(AccessibilityNodeInfo node, Map<Character, AccessibilityNodeInfo> out,
                                      List<AccessibilityNodeInfo> tracker) {
        if (node == null) return;
        String label = getNodeLabel(node);
        if (label != null && label.length() == 1 && label.charAt(0) >= '0' && label.charAt(0) <= '9') {
            char digit = label.charAt(0);
            if (!out.containsKey(digit) && (node.isClickable() || node.isEnabled())) {
                AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(node);
                out.put(digit, copy);
                if (tracker != null) tracker.add(copy);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectDigitButtons(child, out, tracker);
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private String getNodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) return desc.toString().trim();
        return null;
    }

    private AccessibilityNodeInfo findPasswordField(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> tracker) {
        if (node == null) return null;
        if (node.isPassword()) {
            AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(node);
            if (tracker != null) tracker.add(copy);
            return copy;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findPasswordField(child, tracker);
                if (found != null) {
                    try { child.recycle(); } catch (Exception ignored) {}
                    return found;
                }
                try { child.recycle(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> tracker) {
        AccessibilityNodeInfo current = node;
        List<AccessibilityNodeInfo> parents = new ArrayList<>();
        try {
            for (int depth = 0; depth < 5 && current != null; depth++) {
                if (current.isClickable()) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (parent != null) parents.add(parent);
                current = parent;
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            for (AccessibilityNodeInfo p : parents) {
                try { p.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private AccessibilityNodeInfo getRootWithRetry() {
        for (int attempt = 0; attempt < ROOT_RETRY_COUNT; attempt++) {
            try {
                AccessibilityNodeInfo root = host.getRootInActiveWindow();
                if (root != null) return root;
                List<android.view.accessibility.AccessibilityWindowInfo> windows = host.getWindows();
                if (windows != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                        if (w != null) {
                            root = w.getRoot();
                            if (root != null) return root;
                        }
                    }
                }
            } catch (Exception ignored) {}
            Log.d(TAG, "Root retry " + (attempt + 1) + "/" + ROOT_RETRY_COUNT);
            sleep(ROOT_RETRY_DELAY_MS);
        }
        return null;
    }

    private void tapAt(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS);
        GestureDescription gesture =
            new GestureDescription.Builder().addStroke(stroke).build();
        host.dispatchGesture(gesture, null, null);
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
