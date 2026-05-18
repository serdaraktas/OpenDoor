package com.zkteko.kapi;

import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

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

        doorBtn      = findViewById(R.id.doorBtn);
        btnLabel     = findViewById(R.id.btnLabel);
        logMsg       = findViewById(R.id.logMsg);
        timestampView= findViewById(R.id.timestamp);
        statusDot    = findViewById(R.id.statusDot);
        settingsPanel= findViewById(R.id.settingsPanel);
        cfgHost      = findViewById(R.id.cfgHost);
        cfgUser      = findViewById(R.id.cfgUser);
        cfgPass      = findViewById(R.id.cfgPass);

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
            }
        });
    }

    private void openDoor() {
        if (loading) return;

        String host = normalizeHost(getHost());
        String user = getUser();
        String pass = getPass();

        setLoading(true);
        setLog("Cihaza bağlanıyor...", "");
        setStatus("");

        new Thread(() -> {
            try {
                // ZKTeco SC403 arayüzü bazı sürümlerde login cookie ister, bazı sürümlerde
                // sadece adminpwd alanı ile /form/Device?act=22 çağrısını kabul eder.
                // Bu yüzden önce tarayıcı davranışına en yakın akışı deneriz.

                String cookie = "";

                // 1) Login sayfasına gir: bazı cihazlar ilk cookie'yi burada verir.
                HttpResult firstPage = httpRequest("GET", "http://" + host + "/csl/login", null, null);
                cookie = mergeCookies(cookie, firstPage.cookies);

                // 2) Login dene. Bilinen alan adları: username + userpwd.
                setLog("Giriş yapılıyor...", "");
                String loginBody = "username=" + encode(user) + "&userpwd=" + encode(pass);
                HttpResult loginResult = httpRequest("POST", "http://" + host + "/csl/login", loginBody, cookie);
                cookie = mergeCookies(cookie, loginResult.cookies);

                // Bazı firmware'lerde alan isimleri farklı olabiliyor. İlk deneme başarısız gibi
                // görünürse alternatif alan isimleriyle de login deneriz.
                if (looksLikeLoginPage(loginResult.body)) {
                    String altLoginBody = "user=" + encode(user) + "&pwd=" + encode(pass);
                    HttpResult altLogin = httpRequest("POST", "http://" + host + "/csl/login", altLoginBody, cookie);
                    cookie = mergeCookies(cookie, altLogin.cookies);
                }

                // 3) Kapı açma komutu. Tarayıcıda görünen istek:
                // POST http://192.168.1.97/form/Device?act=22
                // adminpwd=1140
                setLog("Kapı açma komutu gönderiliyor...", "");
                String doorBody = "adminpwd=" + encode(pass);
                HttpResult doorResult = httpRequest("POST", "http://" + host + "/form/Device?act=22", doorBody, cookie);

                // Cookie istemeyen cihazlar için direkt POST yedeği.
                if (!isProbablyOk(doorResult)) {
                    doorResult = httpRequest("POST", "http://" + host + "/form/Device?act=22", doorBody, null);
                }

                // Bazı cihazlarda form verisi yerine query string kabul ediliyor.
                if (!isProbablyOk(doorResult)) {
                    doorResult = httpRequest("GET", "http://" + host + "/form/Device?act=22&adminpwd=" + encode(pass), null, cookie);
                }

                setLoading(false);

                if (isProbablyOk(doorResult)) {
                    setLog("Komut gönderildi ✓ HTTP " + doorResult.status, "ok");
                    setStatus("online");
                    runOnUiThread(() -> btnLabel.setText("KAPIYI AÇ"));
                    handler.postDelayed(() -> setLog("Hazır", ""), 3000);
                } else {
                    String msg = "Hata HTTP " + doorResult.status;
                    if (doorResult.body != null && doorResult.body.length() > 0) {
                        msg += ": " + shortText(doorResult.body);
                    }
                    setLog(msg, "err");
                    setStatus("error");
                    runOnUiThread(() -> btnLabel.setText("KAPIYI AÇ"));
                    handler.postDelayed(() -> { setLog("Hazır", ""); setStatus(""); }, 6000);
                }

            } catch (Exception e) {
                setLoading(false);
                setLog("Bağlantı hatası: " + e.getMessage(), "err");
                setStatus("error");
                runOnUiThread(() -> btnLabel.setText("KAPIYI AÇ"));
                handler.postDelayed(() -> { setLog("Hazır", ""); setStatus(""); }, 6000);
            }
        }).start();
    }

    private String normalizeHost(String host) {
        if (host == null) return "192.168.1.97";
        host = host.trim();
        host = host.replace("http://", "").replace("https://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        return host;
    }

    private boolean looksLikeLoginPage(String body) {
        if (body == null) return false;
        String b = body.toLowerCase(java.util.Locale.US);
        return b.contains("login") || b.contains("userpwd") || b.contains("password") || b.contains("adminpwd");
    }

    private boolean isProbablyOk(HttpResult r) {
        if (r == null) return false;
        if (!(r.status == 200 || r.status == 204 || r.status == 302)) return false;
        // 200 dönüp tekrar login sayfası geldiyse komut gitmemiş olabilir.
        return !looksLikeLoginPage(r.body);
    }

    private String mergeCookies(String existing, List<String> newCookies) {
        StringBuilder sb = new StringBuilder();
        if (existing != null && !existing.isEmpty()) sb.append(existing);
        if (newCookies != null) {
            for (String c : newCookies) {
                if (c == null || c.length() == 0) continue;
                String first = c.split(";", 2)[0];
                if (first.length() == 0) continue;
                if (sb.indexOf(first) >= 0) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(first);
            }
        }
        return sb.toString();
    }

    private String shortText(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 80) return s.substring(0, 80) + "...";
        return s;
    }

    private HttpResult httpRequest(String method, String urlStr, String body, String cookie) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ZKTekoDoor/1.1");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,*/*");
        conn.setRequestProperty("Referer", "http://" + normalizeHost(getHost()) + "/csl/login");
        conn.setRequestProperty("Origin", "http://" + normalizeHost(getHost()));
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }

        int status = conn.getResponseCode();
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");

        StringBuilder response = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    status >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) response.append(line).append('\n');
            br.close();
        } catch (Exception ignored) { }

        conn.disconnect();
        return new HttpResult(status, cookies, response.toString());
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    static class HttpResult {
        int status;
        List<String> cookies;
        String body;
        HttpResult(int s, List<String> c, String b) {
            status = s;
            cookies = c;
            body = b;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(timeRunnable);
    }
}
