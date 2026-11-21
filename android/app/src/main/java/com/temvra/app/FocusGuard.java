package com.temvra.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.util.Set;

/**
 * Servicio de accesibilidad de Enfoque: solo mira qué app pasa a primer plano (no lee el contenido de la pantalla).
 * Si es una de las que distraen y ya se ha gastado el tiempo de la sesión o el de esta hora, la tapa con FocusBlock,
 * que lleva al escritorio. Mientras esa app sigue delante, vuelve a mirar justo cuando se acabaría el tiempo.
 */
public class FocusGuard extends AccessibilityService {
    static FocusGuard live;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = () -> check(false);
    private String cur = "";
    private String lastBlock = "";
    private long lastBlockAt;
    /** Ajustes, el instalador, Google Play… nunca se bloquean (ver Focus.protectedPkgs). */
    private Set<String> safe = new java.util.HashSet<>();

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) h.removeCallbacks(tick);
            else check(false);
        }
    };

    @Override
    protected void onServiceConnected() {
        live = this;
        safe = Focus.protectedPkgs(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screen, f);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (live == this) live = null;
        h.removeCallbacks(tick);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        return super.onUnbind(intent);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (ignored(p)) return;
        if (!p.equals(cur)) { cur = p; check(true); }
    }

    /** La barra de notificaciones, el teclado y la propia Temvra no cambian la app que se está usando. */
    private boolean ignored(String p) {
        if (p.equals(getPackageName()) || p.equals("com.android.systemui") || p.equals("android")) return true;
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(p + "/");
    }

    /** La app ha cambiado la configuración (sesión nueva, otras apps, otro límite). */
    void recheck() { h.post(() -> check(false)); }

    /**
     * fromEvent: la app acaba de pasar delante según el evento. En las comprobaciones con retraso se confirma con
     * el uso del sistema, por si la pantalla se apagó o se volvió al escritorio sin evento.
     */
    private void check(boolean fromEvent) {
        h.removeCallbacks(tick);
        JSONObject cfg = Focus.cfg(this);
        Set<String> apps = Focus.apps(cfg);
        apps.removeAll(safe);
        if (apps.isEmpty() || !apps.contains(cur)) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        long now = System.currentTimeMillis();
        if (!fromEvent && Focus.hasUsage(this)) {
            String[] fg = new String[1];
            Focus.usage(this, now - 60_000, now, fg);
            if (fg[0] != null && !fg[0].equals(cur)) { if (!ignored(fg[0])) cur = fg[0]; if (!apps.contains(cur)) return; }
        }
        Focus.Verdict v = Focus.verdict(this, cfg, now);
        if (v.block) block(cfg, v, cur);
        else if (v.next > 0) h.postDelayed(tick, Math.min(v.next, 5 * 60_000L));
    }

    private void block(JSONObject cfg, Focus.Verdict v, String pkg) {
        long now = System.currentTimeMillis();
        // Volver al escritorio también genera eventos: no repetir la pantalla en cadena
        if (pkg.equals(lastBlock) && now - lastBlockAt < 1500) return;
        lastBlock = pkg;
        lastBlockAt = now;
        // FocusBlock tapa la app; no se manda antes al escritorio: esa orden llega tarde y taparía la pantalla de aviso
        Intent it = new Intent(this, FocusBlock.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("reason", v.reason)
                .putExtra("until", v.until)
                .putExtra("app", Focus.label(this, pkg));
        try { startActivity(it); } catch (Exception e) { performGlobalAction(GLOBAL_ACTION_HOME); }
        cur = "";
    }
}
