package com.zkteko.kapi;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "zkteko_prefs";

    private View doorBtn;
    private TextView btnLabel, logMsg, timestampView, statusDot;
    private LinearLayout settingsPanel;
    private EditText cfgHost, cfgUser, cfgPass;

    private boolean settingsOpen = false;
    private boolean loading = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
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
        setLog("Hazır", "");
    }

    private void startClock() {
        timeRunnable = new Runnable() {
            @Override
            public void run() {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("HH:mm:ss", new Locale("tr"));
                timestampView.setText(sdf.format(new java.util.Date()));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeRunnable);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String getHost() {
        String host = prefs().getString("host", "192.168.1.97");
        if (host == null) return "192.168.1.97";

        host = host.trim();
        host = host.replace("http://", "");
        host = host.replace("https://", "");
        host = host.replace("/", "");

        return host;
    }

    private String getUser() {
        return prefs().getString("user", "1");
    }

    private String getPass() {
        return prefs().getString("pass", "1140");
    }

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

    private void openDoor() {
        if (loading) return;

        String host = getHost();
        String user = getUser();
        String pass = getPass();

        setLoading(true);
        setStatus("");
        setLog("Cihaza bağlanıyor...", "");

        new Thread(() -> {
            try {
                String baseUrl = "http://" + host;

                /*
                 * Tarayıcıdaki gerçek akış:
                 *
                 * GET  /csl/login
                 * POST /csl/check
                 * GET  /form/Device?act=9
                 * POST /form/Device?act=22
                 */

                // 1) Login sayfasını aç, SessionID cookie al
                HttpResult loginPage = httpGet(
                        baseUrl + "/csl/login",
                        null,
                        baseUrl + "/csl/login",
                        host
                );

                String cookie = buildCookie(loginPage.cookies);

                setLog("Giriş yapılıyor...", "");

                // 2) Login formunu gönder
                String loginBody =
                        "username=" + encode(user) +
                        "&userpwd=" + encode(pass);

                HttpResult loginCheck = httpPost(
                        baseUrl + "/csl/check",
                        loginBody,
                        cookie,
                        baseUrl + "/csl/login",
                        host
                );

                String loginCookie = buildCookie(loginCheck.cookies);
                if (!loginCookie.isEmpty()) {
                    cookie = mergeCookie(cookie, loginCookie);
                }

                // Bazı cihazlarda login başarılı olsa da sadece HTML döner.
                // Bu yüzden status 200 kabul ediyoruz.
                if (loginCheck.status != 200 && loginCheck.status != 302) {
                    throw new Exception("Login başarısız. HTTP " + loginCheck.status);
                }

                setLog("Kapı ekranı açılıyor...", "");

                // 3) Kapı açma şifre ekranına gir
                HttpResult doorPage = httpGet(
                        baseUrl + "/form/Device?act=9",
                        cookie,
                        baseUrl + "/csl/menu",
                        host
                );

                String doorPageCookie = buildCookie(doorPage.cookies);
                if (!doorPageCookie.isEmpty()) {
                    cookie = mergeCookie(cookie, doorPageCookie);
                }

                setLog("Kapı açma komutu gönderiliyor...", "");

                // 4) Kapı açma komutunu gönder
                String doorBody = "adminpwd=" + encode(pass);

                HttpResult openResult = httpPost(
                        baseUrl + "/form/Device?act=22",
                        doorBody,
                        cookie,
                        baseUrl + "/form/Device?act=9",
                        host
                );

                setLoading(false);

                boolean opened =
                        openResult.status == 200 &&
                        openResult.body != null &&
                        openResult.body.toLowerCase(Locale.ROOT).contains("door has been opened");

                if (opened) {
                    setLog("Kapı açıldı ✓", "ok");
                    setStatus("online");
                    handler.postDelayed(() -> setLog("Hazır", ""), 3000);
                } else if (openResult.status == 200) {
                    setLog("Komut gitti ama cevap doğrulanamadı. HTTP 200", "ok");
                    setStatus("online");
                    handler.postDelayed(() -> setLog("Hazır", ""), 4000);
                } else {
                    setLog("Kapı açılamadı. HTTP " + openResult.status, "err");
                    setStatus("error");
                    handler.postDelayed(() -> {
                        setLog("Hazır", "");
                        setStatus("");
                    }, 5000);
                }

            } catch (Exception e) {
                setLoading(false);
                setStatus("error");
                setLog("Hata: " + e.getMessage(), "err");

                handler.postDelayed(() -> {
                    setLog("Hazır", "");
                    setStatus("");
                }, 5000);
            }
        }).start();
    }

    private HttpResult httpGet(String urlStr, String cookie, String referer, String host) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);

        setCommonHeaders(conn, host, referer);

        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }

        int status = conn.getResponseCode();
        String body = readBody(conn);
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");

        conn.disconnect();

        return new HttpResult(status, cookies, body);
    }

    private HttpResult httpPost(
            String urlStr,
            String body,
            String cookie,
            String referer,
            String host
    ) throws Exception {

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);

        setCommonHeaders(conn, host, referer);

        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", String.valueOf(body.getBytes("UTF-8").length));

        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
            os.flush();
        }

        int status = conn.getResponseCode();
        String responseBody = readBody(conn);
        List<String> cookies = conn.getHeaderFields().get("Set-Cookie");

        conn.disconnect();

        return new HttpResult(status, cookies, responseBody);
    }

    private void setCommonHeaders(HttpURLConnection conn, String host, String referer) {
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("Cache-Control", "max-age=0");
        conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
        conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        );
        conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                        "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        );
        conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Connection", "keep-alive");

        if (referer != null && !referer.isEmpty()) {
            conn.setRequestProperty("Referer", referer);
        }
    }

    private String readBody(HttpURLConnection conn) {
        try {
            InputStream is;

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }

            if (is == null) return "";

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            reader.close();
            return sb.toString();

        } catch (Exception e) {
            return "";
        }
    }

    private String buildCookie(List<String> cookies) {
        if (cookies == null || cookies.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        for (String c : cookies) {
            if (c == null || c.trim().isEmpty()) continue;

            String firstPart = c.split(";")[0];

            if (sb.length() > 0) {
                sb.append("; ");
            }

            sb.append(firstPart);
        }

        return sb.toString();
    }

    private String mergeCookie(String oldCookie, String newCookie) {
        if (oldCookie == null || oldCookie.isEmpty()) return newCookie == null ? "" : newCookie;
        if (newCookie == null || newCookie.isEmpty()) return oldCookie;

        if (oldCookie.contains(newCookie)) return oldCookie;

        return oldCookie + "; " + newCookie;
    }

    private String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void setLog(String msg, String type) {
        runOnUiThread(() -> {
            logMsg.setText(msg);

            switch (type) {
                case "ok":
                    logMsg.setTextColor(0xFFC8F544);
                    break;
                case "err":
                    logMsg.setTextColor(0xFFFF4D4D);
                    break;
                default:
                    logMsg.setTextColor(0xFF666666);
                    break;
            }
        });
    }

    private void setStatus(String state) {
        runOnUiThread(() -> {
            switch (state) {
                case "online":
                    statusDot.setTextColor(0xFFC8F544);
                    break;
                case "error":
                    statusDot.setTextColor(0xFFFF4D4D);
                    break;
                default:
                    statusDot.setTextColor(0xFF444444);
                    break;
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

    static class HttpResult {
        int status;
        List<String> cookies;
        String body;

        HttpResult(int status, List<String> cookies, String body) {
            this.status = status;
            this.cookies = cookies;
            this.body = body;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (timeRunnable != null) {
            handler.removeCallbacks(timeRunnable);
        }
    }
}
