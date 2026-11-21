package com.temvra.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

/** Pantalla que sale al abrir una app que distrae cuando ya no queda tiempo para ella. */
public class FocusBlock extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_focus_block);
        fill(getIntent());
        findViewById(R.id.fb_home).setOnClickListener(v -> home());
        findViewById(R.id.fb_open).setOnClickListener(v -> {
            try { Alerts.openIntent(this, "alert", Focus.NOTIF, "{\"type\":\"focus\"}").send(); } catch (Exception ignored) {}
            finish();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        fill(intent);
    }

    private void fill(Intent it) {
        JSONObject cfg = Focus.cfg(this);
        boolean hour = "hour".equals(it.getStringExtra("reason"));
        String app = it.getStringExtra("app");
        String t = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(it.getLongExtra("until", System.currentTimeMillis())));
        String title = hour ? Focus.tx(cfg, "hourTitle", "Límite de esta hora alcanzado") : Focus.tx(cfg, "blockTitle", "Se acabó el tiempo para distraerte");
        String body = hour
                ? Focus.tx(cfg, "hourBody", "Ya has usado los {m} min de esta hora en apps que distraen. {app} vuelve a las {t}.")
                : Focus.tx(cfg, "blockBody", "Tu sesión de enfoque sigue hasta las {t}. {app} se podrá abrir entonces.");
        body = body.replace("{t}", t).replace("{app}", app == null ? "" : app).replace("{m}", String.valueOf(cfg.optInt("hour", 0)));
        ((TextView) findViewById(R.id.fb_title)).setText(title);
        ((TextView) findViewById(R.id.fb_body)).setText(body);
        ((TextView) findViewById(R.id.fb_home)).setText(Focus.tx(cfg, "home", "Volver al inicio"));
        ((TextView) findViewById(R.id.fb_open)).setText(Focus.tx(cfg, "open", "Abrir Temvra"));
    }

    private void home() {
        try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        finish();
    }

    @Override
    public void onBackPressed() { home(); }
}
