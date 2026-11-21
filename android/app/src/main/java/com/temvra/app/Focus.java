package com.temvra.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enfoque: sesiones con un tiempo máximo para las apps que distraen y un límite por hora.
 * El uso de cada app sale de UsageStatsManager (permiso «Acceso a datos de uso»); bloquear lo hace FocusGuard,
 * un servicio de accesibilidad. La configuración la manda la app con focusSet y se guarda aquí, porque el
 * servicio funciona aunque la app esté cerrada. Nada sale del móvil.
 */
final class Focus {
    private Focus() {}

    static final String PREFS = "temvra_focus";
    static final String CH = "t_focus";
    static final int NOTIF = 7003;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONObject cfg(Context c) {
        try { return new JSONObject(sp(c).getString("cfg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void save(Context c, JSONObject cfg) {
        sp(c).edit().putString("cfg", cfg.toString()).apply();
        notifySession(c, cfg);
        if (FocusGuard.live != null) FocusGuard.live.recheck();
    }

    static Set<String> apps(JSONObject cfg) {
        Set<String> s = new HashSet<>();
        JSONArray a = cfg.optJSONArray("apps");
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    /**
     * Apps que nunca se bloquean ni se pueden elegir: Ajustes (desde ahí se quita el servicio), el instalador y los
     * permisos (desinstalar), Google Play, el escritorio y la propia Temvra. Se resuelven también los de cada fabricante.
     */
    static Set<String> protectedPkgs(Context c) {
        Set<String> s = new HashSet<>(Arrays.asList(c.getPackageName(), "android", "com.android.systemui", "com.android.settings",
                "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.android.vending"));
        PackageManager pm = c.getPackageManager();
        for (Intent it : new Intent[]{new Intent(Settings.ACTION_SETTINGS), new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.getPackageName())), new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)}) {
            try {
                ResolveInfo r = pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY);
                if (r != null && r.activityInfo != null) s.add(r.activityInfo.packageName);
            } catch (Exception ignored) {}
        }
        return s;
    }

    /** Texto que manda la app ya traducido; si falta, el español. */
    static String tx(JSONObject cfg, String k, String def) {
        JSONObject t = cfg.optJSONObject("tx");
        String v = t != null ? t.optString(k, "") : "";
        return v.isEmpty() ? def : v;
    }

    /* ---------------- Permisos ---------------- */

    static boolean hasUsage(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) return c.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** El servicio de accesibilidad está activado en Ajustes (aunque Android aún no lo haya conectado). */
    static boolean guardOn(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(c, FocusGuard.class);
        for (String s : on.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s.trim());
            if (cn != null && cn.equals(me)) return true;
        }
        return false;
    }

    /* ---------------- Uso de las apps ---------------- */

    /** Tiempo delante de cada app entre from y to, y la app que está delante ahora (fg[0]). */
    static Map<String, Long> usage(Context c, long from, long to, String[] fg) {
        Map<String, Long> out = new HashMap<>();
        UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (um == null || to <= from) return out;
        UsageEvents ev;
        // Desde unas horas antes: una app abierta antes de «from» solo tiene su evento de entrada ahí
        try { ev = um.queryEvents(from - 6 * 3600_000L, to); } catch (Exception e) { return out; }
        if (ev == null) return out;
        UsageEvents.Event e = new UsageEvents.Event();
        String cur = null, cls = null;
        long since = 0;
        while (ev.hasNextEvent()) {
            if (!ev.getNextEvent(e)) break;
            int t = e.getEventType();
            long ts = e.getTimeStamp();
            String p = e.getPackageName(), k = e.getClassName();
            if (t == 1) { // ACTIVITY_RESUMED (MOVE_TO_FOREGROUND antes de Android 10)
                if (cur != null && !cur.equals(p)) { add(out, cur, since, ts, from, to); cur = null; }
                if (cur == null) { cur = p; since = ts; }
                cls = k;
            } else if (t == 2 || t == 23) { // ACTIVITY_PAUSED / ACTIVITY_STOPPED: solo cuenta la actividad que estaba delante
                if (cur != null && cur.equals(p) && (cls == null || k == null || cls.equals(k))) { add(out, cur, since, ts, from, to); cur = null; }
            } else if (t == 16 || t == 17 || t == 26) { // pantalla apagada, bloqueo, apagado
                if (cur != null) { add(out, cur, since, ts, from, to); cur = null; }
            }
        }
        if (cur != null) add(out, cur, since, to, from, to);
        if (fg != null && fg.length > 0) fg[0] = cur;
        return out;
    }

    private static void add(Map<String, Long> m, String p, long a, long b, long from, long to) {
        long s = Math.max(a, from), e = Math.min(b, to);
        if (e > s) { Long v = m.get(p); m.put(p, (v == null ? 0 : v) + (e - s)); }
    }

    static long sum(Map<String, Long> m, Set<String> apps) {
        long t = 0;
        for (String p : apps) { Long v = m.get(p); if (v != null) t += v; }
        return t;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Apps con icono en el escritorio (las que se pueden elegir como distracción), sin Temvra ni el propio escritorio. */
    static JSONArray launchables(Context c, boolean icons) {
        PackageManager pm = c.getPackageManager();
        JSONArray out = new JSONArray();
        Set<String> seen = protectedPkgs(c);
        List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo r : l) {
            String p = r.activityInfo.packageName;
            if (!seen.add(p)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", p);
                o.put("label", r.loadLabel(pm).toString());
                if (icons) o.put("icon", iconB64(r.loadIcon(pm)));
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out;
    }

    static String homePkg(Context c) {
        try {
            ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String iconB64(Drawable d) {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(cv);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        b.recycle();
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }

    /* ---------------- Qué toca hacer ---------------- */

    /** Resultado de mirar los límites: bloquear (y hasta cuándo) o cuándo volver a mirar. */
    static final class Verdict {
        boolean block;
        String reason = "";
        long until, next;
    }

    /**
     * Con una app de distracción delante: ¿se ha pasado del tiempo de la sesión o del límite de esta hora?
     * Sin permiso de uso no se puede medir: solo se cumple la sesión con 0 % de tiempo para distracciones.
     */
    static Verdict verdict(Context c, JSONObject cfg, long now) {
        Verdict v = new Verdict();
        Set<String> apps = apps(cfg);
        apps.removeAll(protectedPkgs(c));
        JSONObject s = cfg.optJSONObject("session");
        boolean usage = hasUsage(c);
        long next = Long.MAX_VALUE;
        if (s != null) {
            long start = s.optLong("start"), end = s.optLong("end"), allow = s.optLong("allow");
            if (now >= start && now < end) {
                long left = allow;
                if (allow > 0) {
                    if (!usage) left = Long.MAX_VALUE;
                    else left = allow - sum(usage(c, start, now, null), apps);
                }
                if (left <= 0) { v.block = true; v.reason = "session"; v.until = end; return v; }
                next = Math.min(next, Math.min(left, end - now));
            } else if (now < start) next = Math.min(next, start - now);
        }
        int hour = cfg.optInt("hour", 0);
        if (hour > 0 && usage) {
            java.util.Calendar k = java.util.Calendar.getInstance();
            k.setTimeInMillis(now);
            k.set(java.util.Calendar.MINUTE, 0);
            k.set(java.util.Calendar.SECOND, 0);
            k.set(java.util.Calendar.MILLISECOND, 0);
            long hs = k.getTimeInMillis();
            long left = hour * 60_000L - sum(usage(c, hs, now, null), apps);
            if (left <= 0) { v.block = true; v.reason = "hour"; v.until = hs + 3600_000L; return v; }
            next = Math.min(next, left);
        }
        v.next = next == Long.MAX_VALUE ? 0 : Math.max(1000, next);
        return v;
    }

    /* ---------------- Notificación de la sesión en marcha ---------------- */

    static void notifySession(Context c, JSONObject cfg) {
        NotificationManager nm = Alerts.nm(c);
        if (nm == null) return;
        JSONObject s = cfg.optJSONObject("session");
        long now = System.currentTimeMillis();
        // El aviso de empezar puede llegar un instante antes de la hora: se da un margen de 2 s
        if (s == null || now + 2000 < s.optLong("start") || now >= s.optLong("end")) { nm.cancel(NOTIF); return; }
        long end = s.optLong("end");
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, tx(cfg, "channel", "Sesión de enfoque"), NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_temvra)
                .setContentTitle("🎯 " + tx(cfg, "title", "Sesión de enfoque"))
                .setContentText(tx(cfg, "body", ""))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(true)
                .setWhen(end)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(Math.max(1000, end - now))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(Alerts.openIntent(c, "alert", NOTIF, "{\"type\":\"focus\"}"));
        try { nm.notify(NOTIF, b.build()); } catch (Exception ignored) {}
    }
}
