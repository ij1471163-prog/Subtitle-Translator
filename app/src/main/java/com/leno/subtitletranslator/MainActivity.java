package com.leno.subtitletranslator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MIC        = 100;
    private static final int REQ_OVERLAY    = 101;
    private static final int REQ_NOTIF      = 102;

    public static final String PREFS            = "subtitle_prefs";
    public static final String KEY_SOURCE_LANG  = "source_lang";
    public static final String KEY_TARGET_LANG  = "target_lang";
    public static final String KEY_FIRST_LAUNCH = "first_launch";

    private Spinner     spinnerSource, spinnerTarget;
    private TextView    tvStatus, tvRemainingTime;
    private Button      btnStart, btnStop;
    private ProgressBar progressBar;
    private UserManager userManager;
    private BillingManager billingManager;

    // ── AudioPlaybackCapture ─────────────────────────────────────
    private static Intent projectionData = null;
    public static Intent getProjectionData() { return projectionData; }

    // ─────────────────────── Lifecycle ───────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        userManager = new UserManager(this);
        billingManager = new BillingManager(this, userManager);
        billingManager.init(new BillingManager.OnBillingListener() {
            @Override public void onPurchaseSuccess(UserManager.Tier tier) {
                runOnUiThread(() -> {
                    updateStatus();
                    android.widget.Toast.makeText(MainActivity.this, "✅ تم الاشتراك بنجاح!", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onPurchaseFailed(String msg) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "فشل الشراء", android.widget.Toast.LENGTH_SHORT).show());
            }
        });
        initUI();
        if (getPrefs().getBoolean(KEY_FIRST_LAUNCH, true)) {
            showPermissionsDialog();
            getPrefs().edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    // ─────────────────────── UI Setup ────────────────────────────

    private void initUI() {
        spinnerSource   = findViewById(R.id.spinnerSource);
        spinnerTarget   = findViewById(R.id.spinnerTarget);
        tvStatus        = findViewById(R.id.tvStatus);
        tvRemainingTime = findViewById(R.id.tvRemainingTime);
        btnStart        = findViewById(R.id.btnStart);
        btnStop         = findViewById(R.id.btnStop);
        progressBar     = findViewById(R.id.progressBar);

        setupSpinners();
        btnStart.setOnClickListener(v -> attemptStart());
        btnStop.setOnClickListener(v -> stopTranslation());
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
        findViewById(R.id.btnSubscribe).setOnClickListener(v -> showUpgradeDialog());
        updateStatus();
    }

    private void setupSpinners() {
        ArrayAdapter<CharSequence> src = ArrayAdapter.createFromResource(
                this, R.array.source_lang_labels, android.R.layout.simple_spinner_item);
        src.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSource.setAdapter(src);

        ArrayAdapter<CharSequence> tgt = ArrayAdapter.createFromResource(
                this, R.array.target_lang_labels, android.R.layout.simple_spinner_item);
        tgt.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTarget.setAdapter(tgt);

        restoreSpinner(spinnerSource, R.array.source_lang_codes, KEY_SOURCE_LANG, "en-US");
        restoreSpinner(spinnerTarget, R.array.target_lang_codes, KEY_TARGET_LANG, "ar");
    }

    private void restoreSpinner(Spinner spinner, int arrayRes, String prefKey, String def) {
        String saved = getPrefs().getString(prefKey, def);
        String[] codes = getResources().getStringArray(arrayRes);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(saved)) { spinner.setSelection(i); break; }
        }
    }

    // ─────────────────────── Permission Flow ─────────────────────

    private void attemptStart() {
        if (!userManager.canTranslate()) { showUpgradeDialog(); return; }

        // 1. Overlay
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                .setTitle("📺 صلاحية مطلوبة")
                .setMessage("علشان نعرض الترجمة فوق الفيديو، فعّل \"العرض فوق التطبيقات\"")
                .setPositiveButton("تفعيل", (d, w) -> startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY))
                .setNegativeButton("لاحقاً", null)
                .show();
            return;
        }

        // 2. Microphone
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {
                new AlertDialog.Builder(this)
                    .setTitle("🎤 صلاحية الميكروفون")
                    .setMessage("التطبيق يحتاج الميكروفون علشان يسمع الصوت ويترجمه")
                    .setPositiveButton("السماح", (d, w) ->
                        ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC))
                    .setNegativeButton("لاحقاً", null)
                    .show();
            } else {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
            return;
        }

        // 3. Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }

        // 4. AudioPlaybackCapture (Android 10+)
        if (AudioCaptureService.isSupported() && projectionData == null) {
            new AlertDialog.Builder(this)
                .setTitle("🎬 التقاط صوت الفيديو")
                .setMessage("علشان يترجم صوت الفيديو مباشرة، اضغط ابدأ الآن")
                .setPositiveButton("ابدأ", (d, w) ->
                    AudioCaptureService.requestPermission(this))
                .setNegativeButton("بس الميكروفون", (d, w) -> startTranslation())
                .show();
            return;
        }

        startTranslation();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            attemptStart();
        } else {
            Toast.makeText(this, "الصلاحية مطلوبة لتشغيل الترجمة", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            attemptStart();
        } else if (req == AudioCaptureService.REQUEST_CODE) {
            // استقبل إذن Playback Capture
            if (res == RESULT_OK) {
                projectionData = data;
            }
            startTranslation();
        }
    }

    // ─────────────────────── Service Control ─────────────────────

    private void startTranslation() {
        String[] srcCodes = getResources().getStringArray(R.array.source_lang_codes);
        String[] tgtCodes = getResources().getStringArray(R.array.target_lang_codes);
        String src = srcCodes[spinnerSource.getSelectedItemPosition()];
        String tgt = tgtCodes[spinnerTarget.getSelectedItemPosition()];

        getPrefs().edit()
            .putString(KEY_SOURCE_LANG, src)
            .putString(KEY_TARGET_LANG, tgt)
            .apply();

        Intent i = new Intent(this, SubtitleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);

        userManager.startTranslation();
        tvStatus.setText("🟢 الترجمة شغالة — ارجع للفيديو");
        btnStart.setEnabled(false); btnStart.setAlpha(0.4f);
        btnStop.setEnabled(true);  btnStop.setAlpha(1f);
        Toast.makeText(this, "شغّال ✅ ارجع للفيديو", Toast.LENGTH_SHORT).show();
    }

    private void stopTranslation() {
        stopService(new Intent(this, SubtitleService.class));
        userManager.stopTranslation();
        tvStatus.setText("⭕ متوقف");
        btnStart.setEnabled(true); btnStart.setAlpha(1f);
        btnStop.setEnabled(false); btnStop.setAlpha(0.4f);
        updateStatus();
    }

    // ─────────────────────── Dialogs ─────────────────────────────

    private void showPermissionsDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_permissions, null);
        new AlertDialog.Builder(this)
            .setTitle("🔒 صلاحيات التطبيق")
            .setView(v)
            .setPositiveButton("فهمت ✅", null)
            .setCancelable(false)
            .show();
    }

    private void showUpgradeDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_upgrade, null);
        TextView msg      = v.findViewById(R.id.tvUpgradeMessage);
        Button   btnPlus  = v.findViewById(R.id.btnUpgradePlus);
        Button   btnPrem  = v.findViewById(R.id.btnUpgradePremium);
        Button   btnLater = v.findViewById(R.id.btnRemindLater);

        long mins = userManager.getRemainingTime() / 60000;
        msg.setText("استخدمت 30 دقيقة اليوم.\nمتبقي: " + mins
            + " دقيقة\n\nرقِّ للاستمرار بدون حدود!");

        AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        btnPlus.setOnClickListener(x -> {
            billingManager.launchPurchase(this, UserManager.SKU_PLUS_MONTHLY);

            dlg.dismiss();
        });
        btnPrem.setOnClickListener(x -> {
            billingManager.launchPurchase(this, UserManager.SKU_PLUS_MONTHLY);
            dlg.dismiss();
        });
        btnLater.setOnClickListener(x -> dlg.dismiss());
        dlg.show();
    }

    // ─────────────────────── Status ──────────────────────────────

    private void updateStatus() {
        if (userManager.isSubscribed()) {
            tvRemainingTime.setText("✨ مشترك — غير محدود");
            tvRemainingTime.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            long mins = userManager.getRemainingTime() / 60000;
            tvRemainingTime.setText("⏱️ متبقي: " + mins + " دقيقة اليوم");
            tvRemainingTime.setTextColor(
                mins < 5 ? Color.parseColor("#F44336") : Color.parseColor("#FF9800"));
        }
    }

    // ─────────────────────── Helpers ─────────────────────────────

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
