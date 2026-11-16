package com.bjnt.namaztracker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class SetupActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    // ── Update this URL after deploying Apps Script ──
    private static final String API_URL =
        "https://script.google.com/macros/s/AKfycbxdlDvcDYbHumPgTQ1M3awR-cR5KtmPi9Y6VUmnATVo-DkDMQAd7hEEOo_ZIUymgaE/exec";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("nt2_prefs", MODE_PRIVATE);

        // Already set up? Go straight to main app
        if (prefs.getBoolean("setup_done", false)) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_setup);
        setupUI();
    }

    private void setupUI() {
        // Step indicators
        showStep(1);

        EditText codeInput = findViewById(R.id.codeInput);
        EditText nameInput = findViewById(R.id.nameInput);
        Button   verifyBtn = findViewById(R.id.verifyBtn);
        Button   proceedBtn= findViewById(R.id.proceedBtn);
        TextView statusTv  = findViewById(R.id.statusTv);
        View     step1View = findViewById(R.id.step1);
        View     step2View = findViewById(R.id.step2);

        // STEP 1: Verify activation code
        verifyBtn.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (code.isEmpty()) {
                showError(statusTv, "Activation code likhein");
                return;
            }
            verifyBtn.setEnabled(false);
            verifyBtn.setText("Verify ho raha hai...");
            showStatus(statusTv, "⏳ Server se check kar rahe hain...", "#fbbf24");

            // Also accept code from Netlify config
            String savedCodes = prefs.getString("valid_codes","bjnt2026,namaz786");
            boolean localValid = false;
            for(String c : savedCodes.split(",")) {
                if(c.trim().equalsIgnoreCase(code)) { localValid=true; break; }
            }
            if(localValid) {
                prefs.edit().putString("activation_code",code).apply();
                showStep(2);
                showStatus(statusTv,"✅ Code sahi hai!","#22c55e");
                verifyBtn.setEnabled(true); verifyBtn.setText("Verify Karein");
                return;
            }
            new VerifyCodeTask(code, result -> runOnUiThread(() -> {
                if (result.isValid) {
                    prefs.edit().putString("activation_code", code).apply();
                    showStep(2);
                    showStatus(statusTv, "✅ Code sahi hai!", "#22c55e");
                } else {
                    showError(statusTv, "❌ Code galat hai");
                    verifyBtn.setEnabled(true);
                    verifyBtn.setText("Verify Karein");
                }
            })).execute();
        });

        // STEP 2: Enter name and complete setup
        proceedBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                showError(statusTv, "Apna naam likhein");
                return;
            }
            String uid = "u_" + UUID.randomUUID().toString().substring(0, 8);
            String code = prefs.getString("activation_code", "");

            proceedBtn.setEnabled(false);
            proceedBtn.setText("Setup ho raha hai...");
            showStatus(statusTv, "⏳ Account bana rahe hain...", "#fbbf24");

            new RegisterTask(uid, name, code, result -> runOnUiThread(() -> {
                if (result.ok) {
                    prefs.edit()
                        .putString("user_id",   uid)
                        .putString("user_name", name)
                        .putString("api_url",   API_URL)
                        .putBoolean("setup_done", true)
                        .apply();
                    showStatus(statusTv, "✅ Tayyar hai! App chal rahi hai...", "#22c55e");
                    new android.os.Handler().postDelayed(this::goToMain, 1500);
                } else {
                    // Even if registration fails, still proceed (offline mode)
                    prefs.edit()
                        .putString("user_id",   uid)
                        .putString("user_name", name)
                        .putString("api_url",   API_URL)
                        .putBoolean("setup_done", true)
                        .apply();
                    goToMain();
                }
            })).execute();
        });
    }

    private void showStep(int step) {
        View s1 = findViewById(R.id.step1);
        View s2 = findViewById(R.id.step2);
        if (s1 != null) s1.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        if (s2 != null) s2.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
    }

    private void showStatus(TextView tv, String msg, String color) {
        if (tv == null) return;
        tv.setText(msg);
        tv.setTextColor(android.graphics.Color.parseColor(color));
        tv.setVisibility(View.VISIBLE);
    }

    private void showError(TextView tv, String msg) {
        showStatus(tv, msg, "#ef4444");
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ── AsyncTask: Verify Code ──
    static class VerifyResult { boolean isValid; }
    class VerifyCodeTask extends AsyncTask<Void, Void, VerifyResult> {
        String code; interface Callback { void done(VerifyResult r); }
        Callback cb;
        VerifyCodeTask(String c, Callback cb) { this.code=c; this.cb=cb; }
        @Override protected VerifyResult doInBackground(Void... v) {
            VerifyResult r = new VerifyResult();
            try {
                URL u = new URL(API_URL + "?action=validate&code=" + code);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while((line=br.readLine())!=null) sb.append(line);
                JSONObject j = new JSONObject(sb.toString());
                r.isValid = j.optBoolean("valid", false);
                c.disconnect();
            } catch(Exception e) { r.isValid = false; }
            return r;
        }
        @Override protected void onPostExecute(VerifyResult r) { cb.done(r); }
    }

    // ── AsyncTask: Register User ──
    static class RegResult { boolean ok; String uid; }
    class RegisterTask extends AsyncTask<Void, Void, RegResult> {
        String uid, name, code;
        interface Callback { void done(RegResult r); }
        Callback cb;
        RegisterTask(String uid, String name, String code, Callback cb) {
            this.uid=uid; this.name=name; this.code=code; this.cb=cb;
        }
        @Override protected RegResult doInBackground(Void... v) {
            RegResult r = new RegResult();
            try {
                URL u = new URL(API_URL);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                JSONObject body = new JSONObject();
                body.put("action", "register_user");
                body.put("uid",    uid);
                body.put("name",   name);
                body.put("code",   code);
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes());
                os.flush();
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while((line=br.readLine())!=null) sb.append(line);
                JSONObject res = new JSONObject(sb.toString());
                r.ok  = res.optBoolean("ok", false);
                r.uid = res.optString("uid", uid);
                c.disconnect();
            } catch(Exception e) { r.ok = false; }
            return r;
        }
        @Override protected void onPostExecute(RegResult r) { cb.done(r); }
    }
}
