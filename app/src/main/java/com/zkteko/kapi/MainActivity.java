package com.zkteko.kapi;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "zkteko_prefs";

    private View doorBtn;
    private TextView btnLabel, logMsg, timestampView, statusDot;
    private LinearLayout settingsPanel;
    private EditText cfgHost, cfgUser, cfgPass;
    private boolean settingsOpen = false;
    private boolean loading = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        doorBtn       = findViewById(R.id.doorBtn);
        btnLabel      = findViewById(R.id.btnLabel);
        logMsg        = findViewById(R.id.logMsg);
        timestampView = findViewById(R.id.timestamp);
        statusDot     = findViewById(R.id.statusDot);
        settingsPanel = findViewById(R.id.settingsPanel);
        cfgHost       = findViewById(R.id.cfgHost);
        cfgUser       = findViewById(R.id.cfgUser);
        cfgPass       = findViewById(R.id.cfgPass);

        doorBtn.setOnClickListener(v -> openDoor());
        findViewById(R.id.settingsToggle).setOnClickListener(v -> toggleSettings());
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveSettings());

        startClock();
    }

    private void startClock() {
        timeRunnable = new Runnable() {
            @Override public void run() {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", new java.util.Locale("tr"));
                timestampView.setText(sdf.format(new java.util.Date()));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeRunnable);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String getHost() { return prefs().getString("host", "192.168.1.97"); }
    private String getUser() { return prefs().getString("user", "1"); }
    private String getPass() { return prefs().getString("pass", "1140"); }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        if (settingsOpen) {
            cfgHost.setText(getHost());
            cfgUser.setText(getUser());
            cfgPass.setText(getPass());
            settingsPanel.setVisibility(View.VISIBLE);
        } else {
            settingsPanel.setVisibility(View.GONE);
        }
    }

    private void saveSettings() {
        prefs().edit()
            .putString("host", cfgHost.getText().toString().trim())
            .putString("user", cfgUser.getText().toString().trim())
            .putString("pass", cfgPass.getText().toString().trim())
            .apply();
        setLog("Ayarlar kaydedildi ✓", "ok");
        toggleSettings();
    }

    private void setLog(String msg, String type) {
        runOnUiThread(() -> {
            logMsg.setText(msg);
            switch (type) {
                case "ok":  logMsg.setTextColor(0xFFC8F544); break;
                case "err": logMsg.setTextColor(0xFFFF4D4D); break;
                default:    logMsg.setTextColor(0xFF666666); break;
            }
        });
    }

    private void setStatus(String state) {
        runOnUiThread(() -> {
            switch (state) {
                case "online": statusDot.setTextColor(0xFFC8F544); break;
                case "error":  statusDot.setTextColor(0xFFFF4D4D); break;
                default:       statusDot.setTextColor(0xFF444444); break;
            }
        });
    }

    private void setLoading(boolean isLoading) {
        loading = isLoading;
        runOnUiThread(() -> {
            if (isLoading) {
                btnLabel.setText("Bağlanıyor...");
                doorBtn.setAlpha(0.7f);
            } else {
                doorBtn.setAlpha(1.0f);
                btnLabel.setText("KAPIYI AÇ");
            }
        });
    }

    private void openDoor() {
        if (loading) return;
        String host = getHost();
        String user = getUser();
        String pass = getPass();

        setLoading(true);
        setLog("Giriş yapılıyor...", "");
        setStatus("");

        new Thread(() -> {
            try {
                // 1. Login
                String loginBody = "username=" + encode(user) + "&userpwd=" + encode(pass);
                HttpResult loginResult = httpPost("http://" + host + "/csl/login", loginBody, null, host);

                // Cookie al
                String cookie = "";
                if (loginResult.cookies != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String c : loginResult.cookies) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(c.split(";")[0]);
                    }
                    cookie = sb.toString();
                }

                setLog("Kapı açılıyor...", "");

                // 2. Kapı aç
                String doorBody = "adminpwd=" + encode(pass);
                HttpResult doorResult = httpPost("http://" + host + "/form/Device?act=22", doorBody, cookie, host);

                setLoading(false);

                if (doorResult.status == 200 || doorResult.status == 302 || doorResult.status == 204) {
                    setLog("Kapı açıldı ✓", "ok");
                    setStatus("online");
                    handler.postDelayed(() -> setLog("Hazır", ""), 3000);
                } else {
                    setLog("Hata: " + doorResult.status, "err");
                    setStatus("error");
                    handler.postDelayed(() -> { setLog("Hazır", ""); setStatus(""); }, 4000);
                }

            } catch (Exception e) {
                setLoading(false);
                setLog("Bağlantı hatası: " + e.getMessage(), "err");
                setStatus("error");
                handler.postDelayed(() -> { setLog("Hazır", ""); setStatus(""); }, 4000);
            }
        }).start();
    }

    private HttpResult httpPost(String urlStr, String body, String cookie, String host) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Origin", "http://" + host);
        conn.setRequestProperty("Referer", "http://" + host + "/form/Device?act=9");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int status = conn.getResponseCode();
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
        conn.disconnect();
        return new HttpResult(status, cookies);
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    static class HttpResult {
        int status;
        List<String> cookies;
        HttpResult(int s, List<String> c) { status = s; cookies = c; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(timeRunnable);
    }
}
