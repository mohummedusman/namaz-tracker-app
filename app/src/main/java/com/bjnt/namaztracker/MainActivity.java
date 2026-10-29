package com.bjnt.namaztracker;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.webkit.*;
import android.widget.ProgressBar;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipe;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    // ── Your Netlify URL — this is the ONLY thing to update ──────
    private static final String NETLIFY_URL = "https://namaz-ontime.netlify.app/";
    private static final String APP_URL     = NETLIFY_URL + "/";
    private static final String CONFIG_URL  = NETLIFY_URL + "/api-config.json";
    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("nt2_prefs", MODE_PRIVATE);

        if (!prefs.getBoolean("setup_done", false)) {
            // Fetch config first, then show setup
            fetchConfigThenSetup();
            return;
        }

        startMainApp();
    }

    private void fetchConfigThenSetup() {
        new FetchTask(CONFIG_URL, json -> runOnUiThread(() -> {
            try {
                JSONObject cfg = new JSONObject(json);
                String apiUrl = cfg.optString("api_url", "");
                String codes  = cfg.optString("activation_codes", "bjnt2026").replace("[","").replace("]","").replace(""","");
                if (!apiUrl.isEmpty() && !apiUrl.contains("PASTE_YOUR")) {
                    prefs.edit()
                        .putString("api_url_from_config", apiUrl)
                        .putString("valid_codes", codes)
                        .apply();
                }
            } catch (Exception ignored) {}
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        })).execute();
    }

    private void startMainApp() {
        setContentView(R.layout.activity_main);
        webView     = findViewById(R.id.webview);
        swipe       = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        setupWebView();
        webView.loadUrl(APP_URL);
        swipe.setOnRefreshListener(() -> webView.reload());
        swipe.setColorSchemeColors(0xFF1A56DB, 0xFF4A9EFF, 0xFF22C55E);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getPath());
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                if(progressBar!=null) progressBar.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView v, String u) {
                if(progressBar!=null) progressBar.setVisibility(View.GONE);
                if(swipe!=null) swipe.setRefreshing(false);
                injectUserData();
                startGSheetSync();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if(url.startsWith("https://www.google.com/maps")||url.startsWith("geo:")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) {
                cb.invoke(o, true, false);
            }
        });
    }

    private void injectUserData() {
        String uid  = prefs.getString("user_id",   "");
        String name = prefs.getString("user_name", "");
        String api  = prefs.getString("api_url",   "");
        if(api.isEmpty()) api = prefs.getString("api_url_from_config","");
        String js = String.format(
            "if(typeof window.setNativeUser===\'function\'){window.setNativeUser(\'%s\',\'%s\',\'%s\');}",
            uid, name, api);
        webView.evaluateJavascript(js, null);
    }

    private void startGSheetSync() {
        String api = prefs.getString("api_url","");
        if(api.isEmpty()) api = prefs.getString("api_url_from_config","");
        if(api.isEmpty()||api.contains("PASTE_YOUR")) return;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String uid   = prefs.getString("user_id","default");
        String url   = api + "?action=today&date="+today+"&uid="+uid;
        new FetchTask(url, json -> runOnUiThread(() -> {
            String js = "if(typeof window.applyGSheetData===\'function\'){window.applyGSheetData("+json+");}";
            webView.evaluateJavascript(js, null);
        })).execute();
    }

    class NativeBridge {
        @JavascriptInterface
        public void savePrayer(String jsonStr) {
            String api = prefs.getString("api_url","");
            if(api.isEmpty()) api = prefs.getString("api_url_from_config","");
            if(!api.isEmpty()) new PostTask(api, jsonStr, r->{}).execute();
        }
        @JavascriptInterface
        public String getUserInfo() {
            try {
                JSONObject j=new JSONObject();
                j.put("uid",  prefs.getString("user_id",""));
                j.put("name", prefs.getString("user_name",""));
                j.put("api",  prefs.getString("api_url",""));
                return j.toString();
            } catch(Exception e){return "{}";}
        }
        @JavascriptInterface public void refreshFromSheet() { runOnUiThread(()->startGSheetSync()); }
        @JavascriptInterface public void logout() {
            runOnUiThread(()->{
                prefs.edit().putBoolean("setup_done",false).apply();
                startActivity(new Intent(MainActivity.this,SetupActivity.class));
                finish();
            });
        }
    }

    static class FetchTask extends AsyncTask<Void,Void,String> {
        String url; interface Cb{void done(String j);} Cb cb;
        FetchTask(String u,Cb c){url=u;cb=c;}
        @Override protected String doInBackground(Void... v) {
            try{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
                c.setConnectTimeout(10000);c.setReadTimeout(10000);
                BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb=new StringBuilder();String l;
                while((l=br.readLine())!=null)sb.append(l);
                c.disconnect();return sb.toString();
            }catch(Exception e){return "{\"error\":\""+e.getMessage()+"\"}"; }
        }
        @Override protected void onPostExecute(String r){cb.done(r);}
    }

    static class PostTask extends AsyncTask<Void,Void,String> {
        String url,body; interface Cb{void done(String r);} Cb cb;
        PostTask(String u,String b,Cb c){url=u;body=b;cb=c;}
        @Override protected String doInBackground(Void... v) {
            try{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");
                c.setDoOutput(true);c.setConnectTimeout(8000);c.setReadTimeout(8000);
                c.getOutputStream().write(body.getBytes());
                BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb=new StringBuilder();String l;
                while((l=br.readLine())!=null)sb.append(l);
                c.disconnect();return sb.toString();
            }catch(Exception e){return "{\"error\":\""+e.getMessage()+"\"}"; }
        }
        @Override protected void onPostExecute(String r){cb.done(r);}
    }

    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();if(webView!=null)webView.onResume();}
    @Override protected void onPause(){super.onPause();if(webView!=null)webView.onPause();}
}
