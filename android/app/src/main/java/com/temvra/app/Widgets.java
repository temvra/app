package com.temvra.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Pinta los widgets a partir del JSON que guarda la app (SharedPreferences). */
public class Widgets {
    static final String PREFS = "temvra_widgets";
    static final String KEY = "json";
    static final String ACTION_TICK = "com.temvra.app.WIDGET_TICK";
    static final String DAY = "day", BLOCKS = "blocks", NOTES = "notes", LINE = "line", ALARMS = "alarms", REG = "reg";
    static final int DEF_ACCENT = 0xFFC6C4F4;

    /* Solo los usa la sonda de depuración (src/debug, no va en lo publicado): datos, tamaño de letra y medidas de prueba, y dónde dejar las vistas en vez de publicarlas */
    static JSONObject probeData;
    static float probeFs;
    static Bundle probeOpts;
    static List<RemoteViews> probeOut;

    static Bundle opts(AppWidgetManager m, int id) { return probeOpts != null ? probeOpts : m.getAppWidgetOptions(id); }

    private static void publish(AppWidgetManager m, int id, RemoteViews v) {
        if (probeOut != null) probeOut.add(v); else m.updateAppWidget(id, v);
    }

    static JSONObject data(Context c) {
        if (probeData != null) return probeData;
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) { return new JSONObject(); }
    }

    static int count(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, TimerWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, AlarmWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, DayWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, BlocksWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, NotesWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, LineWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, RegWidget.class)).length
             + m.getAppWidgetIds(new ComponentName(c, PulseWidget.class)).length;
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        JSONObject d = data(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TimerWidget.class))) m.updateAppWidget(id, timer(c, d, m, id));
        for (int id : m.getAppWidgetIds(new ComponentName(c, AlarmWidget.class))) updateAlarm(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, DayWidget.class))) updateMonth(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, BlocksWidget.class))) updateList(c, m, id, BLOCKS);
        for (int id : m.getAppWidgetIds(new ComponentName(c, NotesWidget.class))) updateNotes(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, RegWidget.class))) updateReg(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, LineWidget.class))) updateLine(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, PulseWidget.class))) updatePulse(c, m, id);
        scheduleTick(c);
    }

    /* Un «tic» al empezar cada minuto: no despierta el móvil, se entrega en cuanto se enciende la pantalla */
    static void scheduleTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(c);
        if (count(c) == 0) { am.cancel(pi); return; }
        long now = System.currentTimeMillis(), next = (now / 60_000L + 1) * 60_000L;
        /* Si un evento empieza antes del próximo minuto, el tic llega justo entonces: la cuenta atrás de «Ahora» pasa al siguiente sin quedarse en negativo */
        long ev = nextPulseAt(c, now);
        if (ev > now && ev < next) next = ev + 300;
        try { am.setExact(AlarmManager.RTC, next, pi); }
        catch (SecurityException e) { am.set(AlarmManager.RTC, next, pi); }
    }

    static PendingIntent tickIntent(Context c) {
        Intent it = new Intent(c, WidgetTick.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 40, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent open(Context c, String action, int code) {
        Intent it = new Intent(c, MainActivity.class);
        it.setAction("com.temvra.app." + action);
        it.putExtra(TemvraPlugin.EXTRA_ACTION, action);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String mmss(long ms) {
        long s = Math.max(0, (ms + 500) / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Tamaño de letra de los widgets que se elige en Ajustes: 1 es el normal. */
    static float fs(JSONObject d) { return clamp(probeFs > 0 ? probeFs : (float) d.optDouble("fs", 1), .7f, 1.8f); }

    /** Para el texto que va en una caja de alto o ancho fijo: antes de Android 12 la caja no se puede agrandar desde aquí, así que crece menos. */
    static float fsBox(float k) { return Build.VERSION.SDK_INT >= 31 ? k : Math.min(k, 1.12f); }

    static void size(RemoteViews v, int id, float sp) { v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp); }

    private static android.graphics.Typeface sysFace;

    /**
     * Pincel con la letra con la que el sistema pinta los textos. No siempre es la de un Paint recién creado: en ColorOS, con una
     * fuente elegida por el usuario, los textos salen más anchos que lo que ese Paint mide, y lo que se recortaba para caber en una línea ocupaba dos.
     */
    static android.graphics.Paint textPaint(Context c) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        try {
            if (sysFace == null) sysFace = new android.widget.TextView(c.getApplicationContext()).getTypeface();
            if (sysFace != null) p.setTypeface(sysFace);
        } catch (Throwable ignored) {}
        return p;
    }

    /**
     * Alto en dp de una línea de texto de ese tamaño con la letra del sistema, con o sin el margen de la fuente (includeFontPadding).
     * Depende del móvil y de la letra que haya elegido el usuario: con una proporción fija, en algunos el texto salía cortado.
     */
    static float lineDp(Context c, float sp, boolean fontPadding) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        android.graphics.Paint p = textPaint(c);
        p.setTextSize(sp * dm.scaledDensity);
        android.graphics.Paint.FontMetrics f = p.getFontMetrics();
        return (fontPadding ? f.bottom - f.top : f.descent - f.ascent) / dm.density;
    }

    /** Líneas de ese tamaño que caben en el alto libre de una celda del mes (interlineado de 0,92, como en su layout). */
    private static int monthFit(Context c, float room, float sp) {
        float line = lineDp(c, sp, false);
        return room < line ? 0 : 1 + (int) ((room - line) / (line * .92f));
    }

    /* ---------- Temporizador: toda la información, proporcional al tamaño ---------- */
    static RemoteViews timer(Context c, JSONObject d, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_timer);
        Bundle o = opts(m, id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
        JSONObject t = d.optJSONObject("timer");
        long now = System.currentTimeMillis();
        String name = t != null ? t.optString("name", "Temporizador") : "Temporizador";
        boolean running = t != null && t.optBoolean("running", false);
        long endAt = t != null ? t.optLong("endAt", 0) : 0;
        long period = t != null ? t.optLong("period", 90_000) : 90_000;
        long remain = t != null ? t.optLong("remain", period) : period;
        int lap = t != null ? t.optInt("lap", 1) : 1;
        boolean loop = t != null && t.optBoolean("loop", true);
        if (running && endAt > 0 && endAt <= now) {
            if (loop && period > 0) { while (endAt <= now) { endAt += period; lap++; } }
            else { running = false; remain = period; }
        }
        int color = accent(d);
        /* Las cifras ya ocupan lo que cabe: con letra grande solo crecen el nombre y el estado; con letra pequeña encoge todo */
        float k = fs(d), digits = clamp(Math.min(h * .30f, w * .24f), 26, 110), small = clamp(digits * .28f, 12, 26) * Math.min(k, 1.3f);
        digits *= Math.min(k, 1f);
        /* Lo que queda de alto para las cifras: 20 de márgenes, el nombre, el estado, la barra (14), los botones y 3 dp de margen */
        float btns = Build.VERSION.SDK_INT >= 31 ? clamp(h * .26f, 40, 96) : 52f;
        float room = h - 23f - lineDp(c, small, true) - lineDp(c, small * .85f, true) - 14f - btns;
        digits = Math.min(digits, Math.max(20f, room / (lineDp(c, 100f, true) / 100f)));
        v.setTextViewText(R.id.w_name, name);
        v.setTextViewText(R.id.w_lap, (running ? "En marcha" : "Listo") + " · Vuelta " + lap + (loop ? " · Bucle" : ""));
        v.setTextViewTextSize(R.id.w_name, TypedValue.COMPLEX_UNIT_SP, small);
        v.setTextViewTextSize(R.id.w_lap, TypedValue.COMPLEX_UNIT_SP, small * .85f);
        v.setTextColor(R.id.w_lap, color);
        v.setTextViewTextSize(R.id.w_chrono, TypedValue.COMPLEX_UNIT_SP, digits);
        v.setTextViewTextSize(R.id.w_static, TypedValue.COMPLEX_UNIT_SP, digits);
        if (running) {
            v.setViewVisibility(R.id.w_chrono, View.VISIBLE);
            v.setViewVisibility(R.id.w_static, View.GONE);
            v.setChronometerCountDown(R.id.w_chrono, true);
            v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() + (endAt - now), null, true);
            v.setImageViewResource(R.id.w_play, R.drawable.ic_w_pause);
            v.setOnClickPendingIntent(R.id.w_play, Alerts.timerAction(c, "pause", 21));
        } else {
            v.setViewVisibility(R.id.w_chrono, View.GONE);
            v.setViewVisibility(R.id.w_static, View.VISIBLE);
            v.setTextViewText(R.id.w_static, mmss(remain));
            v.setImageViewResource(R.id.w_play, R.drawable.ic_w_play);
            v.setOnClickPendingIntent(R.id.w_play, Alerts.timerAction(c, "resume", 22));
        }
        v.setOnClickPendingIntent(R.id.w_reset, Alerts.timerAction(c, "reset", 23));
        v.setOnClickPendingIntent(R.id.w_skip, Alerts.timerAction(c, "skip", 24));
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            float bh = clamp(h * .26f, 40, 96);
            v.setViewLayoutHeight(R.id.w_btns, bh, TypedValue.COMPLEX_UNIT_DIP);
        }
        int prog = period > 0 ? (int) Math.max(0, Math.min(100, 100 - (running ? (endAt - now) : remain) * 100 / period)) : 0;
        v.setProgressBar(R.id.w_ring, 100, prog, false);
        v.setOnClickPendingIntent(R.id.w_root, open(c, "open", 20));
        return v;
    }

    /* ---------- Alarmas: la lista completa, como en la app ---------- */
    static void updateAlarm(Context c, AppWidgetManager m, int id) {
        Bundle o = opts(m, id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
        float scale = clamp(Math.min(w / 180f, h / 110f), .8f, 1.8f);
        JSONObject d = data(c);
        JSONObject next = d.optJSONObject("alarm");
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_alarm);
        v.setTextViewText(R.id.wa_next, next != null && next.optLong("at", 0) > System.currentTimeMillis() ? next.optString("time", "") + " · " + next.optString("sub", "") : "Sin alarmas activas");
        v.setTextColor(R.id.wa_head, accent(d));
        float kh = Math.min(fs(d), 1.3f);
        size(v, R.id.wa_head, 14f * kh);
        size(v, R.id.wa_next, 11.5f * kh);
        v.setInt(R.id.wa_add, "setColorFilter", accent(d));
        v.setViewVisibility(R.id.wa_headrow, h < 70 ? View.GONE : View.VISIBLE);
        v.setOnClickPendingIntent(R.id.wa_headrow, open(c, "alarms", 30));
        v.setOnClickPendingIntent(R.id.wa_add, open(c, "newalarm", 32));
        v.setOnClickPendingIntent(R.id.wa_empty, open(c, "alarms", 33));
        Intent svc = new Intent(c, WidgetListService.class).putExtra("type", ALARMS).putExtra("scale", scale);
        svc.setData(Uri.parse("temvra://widget/alarms/" + id + "/" + scale));
        setList(c, v, R.id.wa_list, svc);
        v.setEmptyView(R.id.wa_list, R.id.wa_empty);
        v.setPendingIntentTemplate(R.id.wa_list, actionTemplate(c, 4000 + id % 1000));
        publish(m, id, v);
        reloadList(m, id, R.id.wa_list);
    }

    /**
     * Pone las filas de una lista. Desde Android 15 el sistema convierte setRemoteAdapter en filas dentro de la vista, y
     * notifyAppWidgetViewDataChanged vuelve a publicar la vista que tenía guardada: la lista cambiaba, pero la cabecera
     * nueva quedaba pisada por la anterior. Con las filas ya dentro, lista y cabecera llegan juntas en una sola actualización.
     */
    private static void setList(Context c, RemoteViews v, int list, Intent svc) {
        if (Build.VERSION.SDK_INT >= 35) v.setRemoteAdapter(list, WidgetListService.items(c, svc, v.getLayoutId(), list));
        else v.setRemoteAdapter(list, svc);
    }

    /** Después de setList: las versiones anteriores siguen cargando las filas desde el servicio. */
    private static void reloadList(AppWidgetManager m, int id, int list) {
        if (Build.VERSION.SDK_INT < 35) m.notifyAppWidgetViewDataChanged(id, list);
    }

    /** Plantilla común de las listas: WidgetAction decide qué hacer con cada toque. */
    static PendingIntent actionTemplate(Context c, int code) {
        Intent tpl = new Intent(c, WidgetAction.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        int mut = android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getActivity(c, code, tpl, PendingIntent.FLAG_UPDATE_CURRENT | mut);
    }

    /* ---------- Widgets con lista: calendario, bloques y notas ---------- */

    static int accent(JSONObject d) {
        try { return Color.parseColor(d.optString("accent", "#C6C4F4")); }
        catch (Exception e) { return DEF_ACCENT; }
    }

    private static String todayKey(Calendar cal) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static int minutes(String hm) {
        try { return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3, 5)); }
        catch (Exception e) { return -1; }
    }

    /** Filas que pinta la lista. La posición de «ahora» se calcula aquí, así avanza sin abrir la app. */
    static List<JSONObject> rows(JSONObject d, String type) {
        List<JSONObject> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        JSONObject days = d.optJSONObject("days");
        JSONObject day = days != null ? days.optJSONObject(todayKey(cal)) : null;
        JSONArray items = day != null ? day.optJSONArray(type) : null;
        boolean nowDone = false;
        String nowTxt = String.format(Locale.ROOT, "%02d:%02d", nowMin / 60, nowMin % 60);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            int s = minutes(it.optString("s")), e = minutes(it.optString("e"));
            if (e < 0 && s >= 0) e = s + 30;
            if (!nowDone && s > nowMin) { out.add(nowRow(nowTxt)); nowDone = true; }
            JSONObject r = new JSONObject();
            put(r, "s", it.optString("s"));
            put(r, "t", it.optString("t"));
            put(r, "sub", it.optString("sub"));
            put(r, "c", it.optString("c"));
            put(r, "action", it.optString("action"));
            if (s >= 0 && (e <= nowMin || it.optBoolean("done"))) put(r, "past", true);
            if (s >= 0 && s <= nowMin && nowMin < e && !it.optBoolean("done")) {
                put(r, "cur", true);
                put(r, "prog", e > s ? (nowMin - s) * 100 / (e - s) : 0);
                nowDone = true;
            }
            out.add(r);
        }
        if (!nowDone && !out.isEmpty()) out.add(nowRow(nowTxt));
        return out;
    }

    private static JSONObject nowRow(String txt) {
        JSONObject r = new JSONObject();
        put(r, "now", true);
        put(r, "t", txt);
        return r;
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    private static int nowIndex(List<JSONObject> rows) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).optBoolean("now") || rows.get(i).optBoolean("cur")) return i;
        return 0;
    }

    static void updateList(Context c, AppWidgetManager m, int id, String type) {
        JSONObject d = data(c);
        Bundle o = opts(m, id);
        int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180), w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        boolean small = h < 90, narrow = w < 190;
        int color = accent(d);
        Calendar cal = Calendar.getInstance();

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        String title, openAction, addAction, empty;
        if (DAY.equals(type)) { title = "Calendario"; openAction = "agenda"; addAction = "newevent"; empty = "Nada programado hoy"; }
                else { title = "Bloques"; openAction = "blocks"; addAction = "newblock"; empty = "Sin bloques hoy"; }

        v.setViewVisibility(R.id.wl_head, small ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.wl_title, title);
        v.setTextColor(R.id.wl_title, color);
        float k = fs(d), kh = Math.min(k, 1.4f);
        size(v, R.id.wl_title, 14f * kh);
        size(v, R.id.wl_date, 12f * kh);
        size(v, R.id.wl_empty, 13f * k);
        String[] days = {"Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};
        v.setTextViewText(R.id.wl_date, days[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH));
        v.setViewVisibility(R.id.wl_add, narrow ? View.GONE : View.VISIBLE);
        v.setInt(R.id.wl_add, "setColorFilter", color);
        v.setTextViewText(R.id.wl_empty, empty);
        v.setOnClickPendingIntent(R.id.wl_head, open(c, openAction, 80 + (type.hashCode() & 7)));
        v.setOnClickPendingIntent(R.id.wl_add, open(c, addAction, 90 + (type.hashCode() & 7)));
        v.setOnClickPendingIntent(R.id.wl_empty, open(c, openAction, 100 + (type.hashCode() & 7)));

        Intent svc = new Intent(c, WidgetListService.class).putExtra("type", type).putExtra("narrow", narrow);
        svc.setData(Uri.parse("temvra://widget/" + type + "/" + id + "/" + narrow));
        setList(c, v, R.id.wl_list, svc);
        v.setEmptyView(R.id.wl_list, R.id.wl_empty);

        v.setPendingIntentTemplate(R.id.wl_list, actionTemplate(c, 110 + id % 1000));

        {
            // Solo se recoloca la lista cuando «ahora» cambia de fila, para no quitarle el scroll a quien la está mirando
            int idx = nowIndex(rows(d, type));
            String key = "scroll_" + id;
            android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp.getInt(key, -1) != idx) { sp.edit().putInt(key, idx).apply(); v.setScrollPosition(R.id.wl_list, idx); }
        }
        publish(m, id, v);
        reloadList(m, id, R.id.wl_list);
    }

    /* ---------- Línea de tiempo: una fila por hora, dibujada como en la app ---------- */

    private static android.content.SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static int lineOffset(Context c, int id) { return sp(c).getInt("line_off_" + id, 0); }

    /** Días que caben según el ancho del widget. */
    static int lineMaxDays(int wdp) { return wdp < 200 ? 1 : wdp < 300 ? 2 : 3; }

    static int lineDays(Context c, int id, int wdp) {
        int max = lineMaxDays(wdp);
        return Math.max(1, Math.min(max, sp(c).getInt("line_n_" + id, max)));
    }

    /** Alto en dp de cada franja de 15 min: crece con la letra donde el sistema deja cambiarlo (Android 12 o posterior). */
    static float lineRow(float k) { return Build.VERSION.SDK_INT >= 31 ? Math.round(19f * k) : 19f; }

    static int lineWidth(AppWidgetManager m, int id) {
        Bundle o = opts(m, id);
        return Math.max(120, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300));
    }

    /** El ancho que informa el launcher, sin pasar nunca del de la pantalla. */
    static int lineWidth(Context c, AppWidgetManager m, int id) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.min(lineWidth(m, id), (int) (dm.widthPixels / dm.density));
    }

    static Calendar dayAt(int offset) {
        Calendar d = Calendar.getInstance();
        d.add(Calendar.DAY_OF_MONTH, offset);
        return d;
    }

    static JSONArray lineItems(JSONObject d, Calendar day) {
        JSONObject days = d.optJSONObject("days");
        JSONObject o = days != null ? days.optJSONObject(todayKey(day)) : null;
        JSONArray it = o != null ? o.optJSONArray(DAY) : null;
        return it != null ? it : new JSONArray();
    }

    private static PendingIntent lineIntent(Context c, int id, String op, int code) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(AlertReceiver.ACTION_LINE).putExtra("id", id).putExtra("op", op);
        it.setData(Uri.parse("temvra://line/" + id + "/" + op));
        return PendingIntent.getBroadcast(c, 2000 + code + id % 500 * 8, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Días que se ven encima de la lista. */
    private static void lineHeader(RemoteViews v, int color, int off, int n, float kh) {
        String[] names = {"Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};
        int[] heads = {R.id.wline_d0, R.id.wline_d1, R.id.wline_d2};
        for (int i = 0; i < 3; i++) {
            if (i >= n) { v.setViewVisibility(heads[i], View.GONE); continue; }
            Calendar day = dayAt(off + i);
            v.setViewVisibility(heads[i], View.VISIBLE);
            size(v, heads[i], 14f * kh);
            v.setTextViewText(heads[i], names[day.get(Calendar.DAY_OF_WEEK) - 1] + " " + day.get(Calendar.DAY_OF_MONTH));
            v.setTextColor(heads[i], off + i == 0 ? color : 0xFFD4D4DA);
        }
    }

    static void updateLine(Context c, AppWidgetManager m, int id) {
        int w = lineWidth(c, m, id), off = lineOffset(c, id), n = lineDays(c, id, w), max = lineMaxDays(w);
        JSONObject d = data(c);
        int color = accent(d);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_line);
        /* La fila de los días mide 24 dp: la letra crece hasta donde cabe */
        float kh = Math.min(fs(d), 1.3f);
        lineHeader(v, color, off, n, kh);
        float kfoot = w < 300 ? Math.min(kh, 1.1f) : kh;
        size(v, R.id.wline_view, 14f * kfoot);
        size(v, R.id.wline_center, 14f * kfoot);
        v.setOnClickPendingIntent(R.id.wline_days, open(c, "agenda", 120));
        v.setInt(R.id.wline_prev, "setColorFilter", color);
        v.setInt(R.id.wline_next, "setColorFilter", color);
        v.setInt(R.id.wline_add, "setColorFilter", color);
        v.setTextColor(R.id.wline_center, color);
        v.setTextViewText(R.id.wline_view, n == 1 ? "1 día" : n + " días");
        /* En widgets estrechos no caben los cinco controles de abajo y «Ahora» salía cortado: primero se quita el «+» y después el número de días */
        v.setViewVisibility(R.id.wline_view, max > 1 && w >= 215 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.wline_add, w >= 260 ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(R.id.wline_prev, lineIntent(c, id, "prev", 0));
        v.setOnClickPendingIntent(R.id.wline_next, lineIntent(c, id, "next", 1));
        v.setOnClickPendingIntent(R.id.wline_view, lineIntent(c, id, "view", 2));
        v.setOnClickPendingIntent(R.id.wline_center, lineIntent(c, id, "now", 3));
        v.setOnClickPendingIntent(R.id.wline_add, open(c, "newevent", 121));

        Intent svc = new Intent(c, WidgetListService.class).putExtra("type", LINE).putExtra("wdp", w).putExtra("id", id);
        svc.setData(Uri.parse("temvra://widget/line/" + id + "/" + w));
        setList(c, v, R.id.wline_list, svc);
        v.setPendingIntentTemplate(R.id.wline_list, actionTemplate(c, 3000 + id % 1000));
        publish(m, id, v);
        reloadList(m, id, R.id.wline_list);

        // Al colocarlo se centra en la hora actual; después solo con el botón, para respetar el scroll
        if (!sp(c).getBoolean("line_" + id, false)) {
            sp(c).edit().putBoolean("line_" + id, true).apply();
            scrollToNow(c, id, 900);
        }
    }

    /**
     * La lista necesita haberse cargado antes de poder desplazarla, por eso va con retardo. Una lista solo se mueve
     * hasta que la fila asoma, así que primero baja al final y luego sube: al subir, la fila queda arriba y se puede centrar.
     */
    static void scrollToNow(Context c, int id, long delay) {
        Context app = c.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        int rows = Math.max(1, (int) ((opts(m, id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300) - 80) / lineRow(fs(data(app)))));
        Calendar now = Calendar.getInstance();
        int target = Math.max(0, (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) / 15 - rows / 2);
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_line);
            v.setScrollPosition(R.id.wline_list, 95);
            m.partiallyUpdateAppWidget(id, v);
        }, delay);
        h.postDelayed(() -> {
            RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_line);
            v.setScrollPosition(R.id.wline_list, target);
            m.partiallyUpdateAppWidget(id, v);
        }, delay + 600);
    }

    static void lineOp(Context c, int id, String op) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (op != null && op.startsWith("m")) {
            int mo = sp(c).getInt("month_" + id, 0);
            sp(c).edit().putInt("month_" + id, "mprev".equals(op) ? mo - 1 : "mnext".equals(op) ? mo + 1 : 0).apply();
            updateMonth(c, m, id);
            return;
        }
        if ("zoom".equals(op)) {
            sp(c).edit().putInt("zoom_" + id, sp(c).getInt("zoom_" + id, 0) + 1).apply();
            updateNotes(c, m, id);
            return;
        }
        int w = lineWidth(c, m, id), off = lineOffset(c, id);
        if ("prev".equals(op)) sp(c).edit().putInt("line_off_" + id, Math.max(-7, off - 1)).apply();
        else if ("next".equals(op)) sp(c).edit().putInt("line_off_" + id, Math.min(28, off + 1)).apply();
        else if ("view".equals(op)) {
            int n = lineDays(c, id, w), max = lineMaxDays(w);
            sp(c).edit().putInt("line_n_" + id, n >= max ? 1 : n + 1).apply();
        } else if ("now".equals(op)) {
            if (off != 0) sp(c).edit().putInt("line_off_" + id, 0).apply();
            updateLine(c, m, id);
            scrollToNow(c, id, off != 0 ? 500 : 50);
            return;
        }
        updateLine(c, m, id);
    }

    /* ---------- Notas: árbol, bóveda y lupa ---------- */

    private static final float[] ZOOMS = {1f, 1.2f, 1.45f, .85f};

    static float zoomOf(Context c, int id) { return ZOOMS[sp(c).getInt("zoom_" + id, 0) % ZOOMS.length]; }

    static JSONObject vaultOf(Context c, int id, JSONObject d) {
        JSONArray l = d.optJSONArray("vaults");
        if (l == null || l.length() == 0) return null;
        String want = sp(c).getString("vault_" + id, d.optString("curVault"));
        for (int i = 0; i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && o.optString("id").equals(want)) return o; }
        return l.optJSONObject(0);
    }

    static JSONObject regOf(Context c, int id, JSONObject d) {
        JSONArray l = d.optJSONArray("regs");
        if (l == null || l.length() == 0) return null;
        String want = sp(c).getString("reg_" + id, null);
        for (int i = 0; want != null && i < l.length(); i++) { JSONObject o = l.optJSONObject(i); if (o != null && o.optString("id").equals(want)) return o; }
        return l.optJSONObject(0);
    }

    /** Pasa el árbol a filas, respetando las carpetas abiertas de este widget. */
    static void flattenTree(Context c, int id, JSONObject v, String parent, int depth, List<JSONObject> out) {
        String vid = v.optString("id");
        JSONArray folders = v.optJSONArray("folders"), notes = v.optJSONArray("notes");
        JSONObject colors = v.optJSONObject("fc");
        String prefix = parent.isEmpty() ? "" : parent + "/";
        for (int i = 0; folders != null && i < folders.length(); i++) {
            String f = folders.optString(i);
            if (!f.startsWith(prefix) || f.substring(prefix.length()).contains("/") || f.equals(parent)) continue;
            boolean open = sp(c).getBoolean("open_" + id + "_" + vid + "_" + f, false);
            int n = 0;
            for (int k = 0; notes != null && k < notes.length(); k++) {
                String nf = notes.optJSONObject(k).optString("f");
                if (nf.equals(f) || nf.startsWith(f + "/")) n++;
            }
            JSONObject r = new JSONObject();
            put(r, "folder", true);
            put(r, "open", open);
            put(r, "depth", depth);
            put(r, "t", f.substring(prefix.length()));
            put(r, "n", String.valueOf(n));
            put(r, "c", colors != null ? colors.optString(f) : "");
            put(r, "action", "fold:" + id + "|" + f);
            out.add(r);
            if (open) flattenTree(c, id, v, f, depth + 1, out);
        }
        for (int k = 0; notes != null && k < notes.length(); k++) {
            JSONObject n = notes.optJSONObject(k);
            if (n == null || !n.optString("f").equals(parent)) continue;
            JSONObject r = new JSONObject();
            put(r, "depth", depth);
            put(r, "t", n.optString("t"));
            put(r, "c", n.optString("c"));
            put(r, "action", "note:" + vid + "|" + n.optString("id"));
            out.add(r);
        }
    }

    static void toggleFolder(Context c, int id, String path) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        JSONObject v = vaultOf(c, id, data(c));
        if (v == null) return;
        String k = "open_" + id + "_" + v.optString("id") + "_" + path;
        sp(c).edit().putBoolean(k, !sp(c).getBoolean(k, false)).apply();
        updateNotes(c, m, id);
    }

    static void pick(Context c, int id, String kind, String value) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if ("vaults".equals(kind)) { sp(c).edit().putString("vault_" + id, value).apply(); updateNotes(c, m, id); }
        else { sp(c).edit().putString("reg_" + id, value).apply(); updateReg(c, m, id); }
    }

    private static PendingIntent pickIntent(Context c, int id, String kind, int code) {
        Intent it = new Intent(c, WidgetPick.class).putExtra("id", id).putExtra("kind", kind);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        it.setData(Uri.parse("temvra://pick/" + kind + "/" + id));
        return PendingIntent.getActivity(c, code + id % 500, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void updateNotes(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int color = accent(d);
        JSONObject vault = vaultOf(c, id, d);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_notes);
        v.setTextViewText(R.id.wn_vault, (vault != null ? vault.optString("name", "Notas") : "Notas") + "  ▾");
        int tc = color;
        try { if (vault != null && !vault.optString("tc").isEmpty()) tc = Color.parseColor(vault.optString("tc")); } catch (Exception ignored) {}
        v.setTextColor(R.id.wn_vault, tc);
        size(v, R.id.wn_vault, 17f * Math.min(fs(d), 1.4f));
        v.setInt(R.id.wn_zoom, "setColorFilter", color);
        v.setInt(R.id.wn_add, "setColorFilter", color);
        v.setOnClickPendingIntent(R.id.wn_vault, pickIntent(c, id, "vaults", 5000));
        Intent zi = new Intent(c, AlertReceiver.class).setAction(AlertReceiver.ACTION_LINE).putExtra("id", id).putExtra("op", "zoom");
        zi.setData(Uri.parse("temvra://zoom/" + id));
        v.setOnClickPendingIntent(R.id.wn_zoom, PendingIntent.getBroadcast(c, 5600 + id % 400, zi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.wn_add, open(c, "newnote", 5500));
        v.setOnClickPendingIntent(R.id.wn_empty, open(c, "notes", 5501));
        Intent svc = new Intent(c, WidgetListService.class).putExtra("type", NOTES).putExtra("id", id);
        svc.setData(Uri.parse("temvra://widget/notes/" + id));
        setList(c, v, R.id.wn_list, svc);
        v.setEmptyView(R.id.wn_list, R.id.wn_empty);
        v.setPendingIntentTemplate(R.id.wn_list, actionTemplate(c, 6000 + id % 1000));
        publish(m, id, v);
        reloadList(m, id, R.id.wn_list);
    }

    /* ---------- Registro ---------- */

    static void updateReg(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        JSONObject r = regOf(c, id, d);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_reg);
        int color = accent(d);
        try { if (r != null && !r.optString("color").isEmpty()) color = Color.parseColor(r.optString("color")); } catch (Exception ignored) {}
        v.setTextViewText(R.id.wg_name, (r != null ? r.optString("name") : "Registros") + "  ▾");
        v.setTextColor(R.id.wg_name, color);
        v.setTextViewText(R.id.wg_tag, r != null && !r.optString("tag").isEmpty() ? "#" + r.optString("tag") : "");
        float k = fs(d), kh = Math.min(k, 1.3f);
        size(v, R.id.wg_name, 14f * kh);
        size(v, R.id.wg_tag, 11f * kh);
        size(v, R.id.wg_empty, 13f * k);
        v.setInt(R.id.wg_add, "setColorFilter", color);
        v.setOnClickPendingIntent(R.id.wg_name, pickIntent(c, id, "regs", 7000));
        v.setOnClickPendingIntent(R.id.wg_add, open(c, r != null ? "reg:" + r.optString("id") : "regs", 7500));
        v.setOnClickPendingIntent(R.id.wg_empty, open(c, "regs", 7501));
        Intent svc = new Intent(c, WidgetListService.class).putExtra("type", REG).putExtra("id", id);
        svc.setData(Uri.parse("temvra://widget/reg/" + id));
        setList(c, v, R.id.wg_list, svc);
        v.setEmptyView(R.id.wg_list, R.id.wg_empty);
        v.setPendingIntentTemplate(R.id.wg_list, actionTemplate(c, 8000 + id % 1000));
        publish(m, id, v);
        reloadList(m, id, R.id.wg_list);
    }

    /* ---------- Calendario: el mes ---------- */

    private static final int[][] MONTH_CELLS = {
        {R.id.wm_c0, R.id.wm_n0, R.id.wm_t0}, {R.id.wm_c1, R.id.wm_n1, R.id.wm_t1}, {R.id.wm_c2, R.id.wm_n2, R.id.wm_t2},
        {R.id.wm_c3, R.id.wm_n3, R.id.wm_t3}, {R.id.wm_c4, R.id.wm_n4, R.id.wm_t4}, {R.id.wm_c5, R.id.wm_n5, R.id.wm_t5}, {R.id.wm_c6, R.id.wm_n6, R.id.wm_t6}};

    private static PendingIntent monthOp(Context c, int id, String op, int code) {
        Intent it = new Intent(c, AlertReceiver.class).setAction(AlertReceiver.ACTION_LINE).putExtra("id", id).putExtra("op", op);
        it.setData(Uri.parse("temvra://month/" + id + "/" + op));
        return PendingIntent.getBroadcast(c, 9500 + code + id % 100 * 4, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Festivo y eventos del día, una línea por elemento; lo que no cabe queda en «+N más». */
    private static CharSequence monthLines(String hol, JSONArray items, int fit, android.graphics.Paint paint, float lineW, boolean inMonth) {
        List<String> all = new ArrayList<>();
        if (!hol.isEmpty()) all.add(hol);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) all.add(it.optString("t"));
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        if (fit < 1) {
            if (!all.isEmpty()) out.append(String.valueOf(all.size()));
            return out;
        }
        int shown = all.size() > fit ? fit - 1 : all.size();
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append('\n');
            // Cada elemento en su línea: sin recortar, el texto largo saltaría a la siguiente
            String t = all.get(i);
            int cut = paint.breakText(t, true, lineW, null);
            if (cut > 0 && cut < t.length() && Character.isHighSurrogate(t.charAt(cut - 1))) cut--;
            int a = out.length();
            out.append(t, 0, cut);
            if (i == 0 && !hol.isEmpty()) out.setSpan(new android.text.style.ForegroundColorSpan(inMonth ? 0xFFF08A8A : 0x55F08A8A), a, out.length(), 0);
        }
        if (shown < all.size()) {
            if (shown > 0) out.append('\n');
            int a = out.length();
            // En celdas estrechas o con letra grande «+4 más» no cabe en una línea: se queda en «+4»
            String more = "+" + (all.size() - shown) + " más";
            out.append(paint.measureText(more) > lineW ? "+" + (all.size() - shown) : more);
            out.setSpan(new android.text.style.ForegroundColorSpan(inMonth ? 0xFFA0A0A8 : 0x44A0A0A8), a, out.length(), 0);
        }
        return out;
    }

    static void updateMonth(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c), days = d.optJSONObject("days");
        int color = accent(d), start = d.optInt("weekStart", 1);
        Bundle o = opts(m, id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 340);
        float scale = clamp(Math.min(w / 320f, h / 340f), .75f, 1.5f);
        Calendar first = Calendar.getInstance(), now = Calendar.getInstance();
        first.set(Calendar.DAY_OF_MONTH, 1);
        first.add(Calendar.MONTH, sp(c).getInt("month_" + id, 0));
        int month = first.get(Calendar.MONTH);
        String[] names = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_month);
        v.setTextViewText(R.id.wm_title, names[month] + (first.get(Calendar.YEAR) != now.get(Calendar.YEAR) ? " " + first.get(Calendar.YEAR) : ""));
        v.setInt(R.id.wm_prev, "setColorFilter", color);
        v.setInt(R.id.wm_next, "setColorFilter", color);
        v.setInt(R.id.wm_add, "setColorFilter", color);
        v.setOnClickPendingIntent(R.id.wm_prev, monthOp(c, id, "mprev", 0));
        v.setOnClickPendingIntent(R.id.wm_next, monthOp(c, id, "mnext", 1));
        v.setOnClickPendingIntent(R.id.wm_title, monthOp(c, id, "mtoday", 2));
        v.setOnClickPendingIntent(R.id.wm_add, open(c, "newevent", 9490));
        int[] heads = {R.id.wm_d0, R.id.wm_d1, R.id.wm_d2, R.id.wm_d3, R.id.wm_d4, R.id.wm_d5, R.id.wm_d6};
        String[] letters = {"D", "L", "M", "X", "J", "V", "S"};
        float kf = fs(d), kh = Math.min(kf, 1.3f);
        size(v, R.id.wm_title, 20f * kh);
        for (int i = 0; i < 7; i++) { v.setTextViewText(heads[i], letters[(start + i) % 7]); size(v, heads[i], 12f * kh); }
        Calendar cur = (Calendar) first.clone();
        int back = (first.get(Calendar.DAY_OF_WEEK) - 1 - start + 7) % 7;
        cur.add(Calendar.DAY_OF_MONTH, -back);
        String todayK = todayKey(now);
        v.removeAllViews(R.id.wm_rows);
        // Solo las semanas del mes, y letra más pequeña hasta que quepan 6 líneas (5 en los meses de 6 filas).
        // 52 = márgenes + cabecera; después van las letras de la semana. Cada celda: 3 de márgenes, 2 de relleno, el número del día y 1 sobre el texto.
        // Los altos se miden con la letra del sistema: con una proporción fija, en móviles con letras altas la última línea salía cortada
        // Con la letra más grande de Ajustes se piden menos líneas y no se baja de su tamaño mínimo: se ve más grande y lo que no cabe queda en «+N más»
        int weeks = (int) Math.ceil((back + first.getActualMaximum(Calendar.DAY_OF_MONTH)) / 7f);
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float numSp = Math.max(9f, 10.5f * scale) * kf;
        float room = (h - 52f - lineDp(c, 12f * kh, true) - 2f) / weeks - 6f - lineDp(c, numSp, false);
        float minSp = 8f * kf, lineSp = Math.max(8f, 9.5f * scale) * kf;
        int want = Math.max(2, Math.round(6f / kf));
        while (lineSp > minSp && monthFit(c, room, lineSp) < want) lineSp = Math.max(minSp, lineSp - .5f);
        int fit = monthFit(c, room, lineSp);
        android.graphics.Paint paint = textPaint(c);
        paint.setTextSize(lineSp * dm.scaledDensity);
        // Ancho útil de una celda: el del widget sin sus márgenes (12), entre 7, menos los márgenes de la celda (3) y el relleno del texto (2), con 1 dp de margen.
        // Si el texto recortado mide una pizca más que la celda, salta de línea y empuja fuera el «+N más»
        float lineW = ((w - 12f) / 7f - 6f) * dm.density;
        for (int r = 0, pos = 0; r < weeks; r++) {
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_month_row);
            for (int i = 0; i < 7; i++, pos++) {
                int[] ids = MONTH_CELLS[i];
                String k = todayKey(cur);
                boolean inMonth = cur.get(Calendar.MONTH) == month, today = k.equals(todayK);
                int dow = cur.get(Calendar.DAY_OF_WEEK);
                boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
                JSONObject day = days != null ? days.optJSONObject(k) : null;
                JSONArray items = day != null ? day.optJSONArray(DAY) : null;
                int n = items != null ? items.length() : 0;
                String hol = day != null ? day.optString("hol") : "";
                row.setTextViewText(ids[1], String.valueOf(cur.get(Calendar.DAY_OF_MONTH)));
                row.setTextViewTextSize(ids[1], TypedValue.COMPLEX_UNIT_SP, numSp);
                row.setTextColor(ids[1], today ? color : !inMonth ? 0x55F4F4F6 : !hol.isEmpty() || weekend ? 0xFFF08A8A : 0xFFF4F4F6);
                CharSequence lines = monthLines(hol, items, fit, paint, lineW, inMonth);
                row.setTextViewText(ids[2], lines);
                row.setTextViewTextSize(ids[2], TypedValue.COMPLEX_UNIT_SP, lineSp);
                row.setInt(ids[2], "setMaxLines", Math.max(1, fit));
                row.setTextColor(ids[2], inMonth ? 0xFFD4D4DA : 0x55D4D4DA);
                row.setViewVisibility(ids[2], lines.length() == 0 ? View.GONE : View.VISIBLE);
                row.setInt(ids[0], "setBackgroundResource", today ? R.drawable.widget_row_cur : inMonth ? R.drawable.widget_row_bg : R.drawable.wm_out);
                row.setOnClickPendingIntent(ids[0], open(c, "day:" + k, 10000 + (id % 40) * 50 + pos));
                cur.add(Calendar.DAY_OF_MONTH, 1);
            }
            v.addView(R.id.wm_rows, row);
        }
        publish(m, id, v);
    }

    /* ---------- «Ahora»: hora con segundos, el rayo del día y los dos próximos eventos ---------- */
    static long nextPulseAt(Context c, long now) {
        try {
            JSONObject p = data(c).optJSONObject("pulse");
            JSONArray n = p != null ? p.optJSONArray("next") : null;
            long best = 0;
            for (int i = 0; n != null && i < n.length(); i++) { long at = n.optJSONObject(i).optLong("at"); if (at > now && (best == 0 || at < best)) best = at; }
            return best;
        } catch (Exception e) { return 0; }
    }

    private static int colorOr(String s, int def) {
        try { return s != null && s.matches("#[0-9a-fA-F]{6}") ? Color.parseColor(s) : def; } catch (Exception e) { return def; }
    }

    private static int lighten(int c, float k) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Color.rgb((int) (r + (255 - r) * k), (int) (g + (255 - g) * k), (int) (b + (255 - b) * k));
    }

    static String dur(long ms) {
        long m = Math.max(0, Math.round(ms / 60000.0)), d = m / 1440, h = (m % 1440) / 60, mm = m % 60;
        if (d > 0) return d + " d" + (h > 0 ? " " + h + " h" : "");
        if (h > 0) return h + " h" + (mm > 0 ? " " + mm + " min" : "");
        return mm + " min";
    }

    private static int px(Context c, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    private static void pulseRow(Context c, RemoteViews v, JSONObject e, long now, int lz, int row, int bar, int title, int when, int chrono, int text, int code) {
        if (e == null) { v.setViewVisibility(row, View.GONE); return; }
        v.setViewVisibility(row, View.VISIBLE);
        v.setTextViewText(title, e.optString("t"));
        v.setTextViewText(when, e.optString("w"));
        v.setInt(bar, "setColorFilter", colorOr(e.optString("c"), lz));
        long dt = e.optLong("at") - now;
        int lzl = lighten(lz, .3f);
        if (dt < 24L * 3600_000L) {
            v.setViewVisibility(chrono, View.VISIBLE);
            v.setViewVisibility(text, View.GONE);
            v.setChronometerCountDown(chrono, true);
            v.setChronometer(chrono, SystemClock.elapsedRealtime() + dt, null, true);
            v.setTextColor(chrono, lzl);
        } else {
            v.setViewVisibility(chrono, View.GONE);
            v.setViewVisibility(text, View.VISIBLE);
            v.setTextViewText(text, dur(dt));
            v.setTextColor(text, lzl);
        }
        String a = e.optString("a", "agenda");
        v.setOnClickPendingIntent(row, open(c, a.isEmpty() ? "agenda" : a, code));
    }

    static void updatePulse(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c), p = d.optJSONObject("pulse");
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_pulse);
        Bundle o = opts(m, id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
        int lz = colorOr(p != null ? p.optString("laser") : null, 0xFFFF3B43), lzl = lighten(lz, .3f);
        /* La hora ya ocupa lo que cabe a lo ancho: con letra grande crecen los eventos y sus cuentas atrás; la fecha, solo un poco */
        float k = fs(d), kd = Math.min(k, 1.15f);
        float clock = clamp(Math.min(h * .32f, w * .2f), 34, 76) * Math.min(k, 1f);
        size(v, R.id.wp_date, 12f * kd); size(v, R.id.wp_left, 11f * kd); size(v, R.id.wp_lbl, 10f * k); size(v, R.id.wp_empty, 13f * k);
        size(v, R.id.wp_t1, 15f * k); size(v, R.id.wp_w1, 12f * k); size(v, R.id.wp_in1, 9f * k); size(v, R.id.wp_c1, 17f * k); size(v, R.id.wp_x1, 15f * k);
        size(v, R.id.wp_t2, 14f * k); size(v, R.id.wp_w2, 11.5f * k); size(v, R.id.wp_in2, 9f * k); size(v, R.id.wp_c2, 15f * k); size(v, R.id.wp_x2, 14f * k);
        v.setTextColor(R.id.wp_s, lzl);
        Calendar cal = Calendar.getInstance();
        long now = System.currentTimeMillis();
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        float frac = mins / 1440f;
        /* El rayo del día llega hasta ahora: el resto del ancho se deja como relleno a la derecha */
        int inner = Math.max(60, w - 36 - 14);
        v.setViewPadding(R.id.wp_lzw, 0, 0, px(c, (1 - frac) * inner), 0);
        v.setInt(R.id.wp_lz, "setColorFilter", lz);
        v.setInt(R.id.wp_halo, "setColorFilter", lz);
        String leftTxt = "Quedan " + dur((1440 - mins) * 60_000L);
        v.setTextViewText(R.id.wp_left, leftTxt);
        List<JSONObject> up = new ArrayList<>();
        JSONArray n = p != null ? p.optJSONArray("next") : null;
        for (int i = 0; n != null && i < n.length(); i++) { JSONObject e = n.optJSONObject(i); if (e != null && e.optLong("at") > now) up.add(e); }
        java.util.Collections.sort(up, (a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
        /* Qué cabe de verdad, medido con la letra del sistema. Antes se decidía solo por el alto del widget y, según el móvil y la letra, el evento salía cortado por abajo.
           Por orden: dos eventos o uno, con el rótulo «PRÓXIMOS» o sin él; la hora puede encoger hasta un 20 % para hacerles sitio.
           24 = márgenes de arriba y abajo (más 2 dp de margen: si falta sitio, la fila se encoge y corta su texto); 22 = el rayo del día; 16 y 14 = márgenes de cada evento */
        float avail = h - 26f, unit = lineDp(c, 100f, false) / 100f;
        float dateCol = lineDp(c, 12f * kd, true) + 2f + lineDp(c, 11f * kd, true) + 8f;
        float lbl = 8f + lineDp(c, 10f * k, true), empty = 8f + lineDp(c, 13f * k, true);
        float r1 = 16f + Math.max(30f, Math.max(lineDp(c, 15f * k, true) + lineDp(c, 12f * k, true), lineDp(c, 9f * k, true) + lineDp(c, 17f * k, true)));
        float r2 = 14f + Math.max(26f, Math.max(lineDp(c, 14f * k, true) + lineDp(c, 11.5f * k, true), lineDp(c, 9f * k, true) + lineDp(c, 15f * k, true)));
        boolean none = up.isEmpty(), label = false;
        int rows = 0;
        float minClock = Math.max(Math.min(34f, clock), clock * .8f);
        pick:
        for (int nr = none ? 1 : Math.min(2, up.size()); nr >= 1; nr--) for (int withLabel = none ? 0 : 1; withLabel >= 0; withLabel--) {
            float need = none ? empty : (withLabel == 1 ? lbl : 0f) + r1 + (nr == 2 ? r2 : 0f);
            float room = avail - need - 22f;
            if (room < dateCol) continue;
            float fit = Math.min(clock, room / unit);
            if (fit < minClock) continue;
            clock = fit; rows = nr; label = withLabel == 1;
            break pick;
        }
        /* A lo ancho: la hora, los segundos y la columna de la fecha comparten fila. En widgets estrechos la fecha salía cortada por la derecha: encoge la hora */
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        android.graphics.Paint tp = textPaint(c);
        tp.setTextSize(12f * kd * dm.scaledDensity);
        tp.setLetterSpacing(.1f);
        float dateW = tp.measureText(new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new java.util.Date()).toUpperCase(Locale.getDefault()));
        tp.setLetterSpacing(0f);
        tp.setTextSize(11f * kd * dm.scaledDensity);
        dateW = Math.max(dateW, tp.measureText(leftTxt)) / dm.density;
        tp.setTextSize(100f * dm.scaledDensity);
        float perSp = (tp.measureText("00:00") + tp.measureText("00") * .4f) / dm.density / 100f;
        clock = Math.min(clock, Math.max(26f, (w - 36f - dateW - 10f) / perSp));
        v.setTextViewTextSize(R.id.wp_hm, TypedValue.COMPLEX_UNIT_SP, clock);
        v.setTextViewTextSize(R.id.wp_s, TypedValue.COMPLEX_UNIT_SP, clock * .4f);
        boolean small = rows < 2, tiny = rows < 1;
        pulseRow(c, v, tiny || up.isEmpty() ? null : up.get(0), now, lz, R.id.wp_r1, R.id.wp_d1, R.id.wp_t1, R.id.wp_w1, R.id.wp_c1, R.id.wp_x1, 9601);
        pulseRow(c, v, small || up.size() < 2 ? null : up.get(1), now, lz, R.id.wp_r2, R.id.wp_d2, R.id.wp_t2, R.id.wp_w2, R.id.wp_c2, R.id.wp_x2, 9602);
        v.setViewVisibility(R.id.wp_lbl, label ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.wp_empty, !tiny && up.isEmpty() ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(R.id.wp_root, open(c, "agenda", 9600));
        publish(m, id, v);
    }
}
