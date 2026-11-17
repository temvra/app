package com.temvra.app;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Sonda de depuración: pinta los widgets dentro de la app, con datos de ejemplo, y mide si algún texto se corta.
 * Los widgets de verdad viven en el launcher, donde no se pueden medir ni capturar sin enseñar la pantalla de inicio.
 * Solo existe en las compilaciones de depuración (src/debug) y necesita Android 15 o posterior (listas con las filas dentro de la vista).
 *
 *   adb shell am start -n PAQUETE/com.temvra.app.WidgetProbe --ef fs 1.3 [--es only pulse,line] [--ei w 340 --ei h 420]
 *
 * El resultado sale en logcat con la etiqueta TemvraProbe: una línea por widget con el tamaño de cada texto y los que se cortan.
 */
public class WidgetProbe extends Activity {
    private static final String[][] ALL = {
        {"pulse", "340", "180"}, {"line", "340", "400"}, {"month", "340", "360"}, {"day", "260", "210"}, {"blocks", "260", "210"},
        {"notes", "260", "230"}, {"reg", "260", "210"}, {"alarm", "220", "180"}, {"timer", "180", "180"}};

    private final List<Object[]> boxes = new ArrayList<>();
    private float fs = 1f;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        fs = getIntent().getFloatExtra("fs", 1f);
        String only = getIntent().getStringExtra("only");
        int fw = getIntent().getIntExtra("w", 0), fh = getIntent().getIntExtra("h", 0);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF2A2A30);
        col.setPadding(dp(8), dp(34), dp(8), dp(20));
        sv.addView(col);
        setContentView(sv);
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        try {
            Widgets.probeData = sample();
            Widgets.probeFs = fs;
            for (String[] w : ALL) {
                if (only != null && !("," + only + ",").contains("," + w[0] + ",")) continue;
                int ww = fw > 0 ? fw : Integer.parseInt(w[1]), hh = fh > 0 ? fh : Integer.parseInt(w[2]);
                Bundle o = new Bundle();
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, ww);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, ww);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hh);
                o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hh);
                Widgets.probeOpts = o;
                Widgets.probeOut = new ArrayList<>();
                RemoteViews rv = null;
                switch (w[0]) {
                    case "timer": rv = Widgets.timer(this, Widgets.data(this), m, 0); break;
                    case "pulse": Widgets.updatePulse(this, m, 0); break;
                    case "line": Widgets.updateLine(this, m, 0); break;
                    case "month": Widgets.updateMonth(this, m, 0); break;
                    case "day": Widgets.updateList(this, m, 0, Widgets.DAY); break;
                    case "blocks": Widgets.updateList(this, m, 0, Widgets.BLOCKS); break;
                    case "notes": Widgets.updateNotes(this, m, 0); break;
                    case "reg": Widgets.updateReg(this, m, 0); break;
                    case "alarm": Widgets.updateAlarm(this, m, 0); break;
                }
                if (rv == null && !Widgets.probeOut.isEmpty()) rv = Widgets.probeOut.get(0);
                if (rv == null) continue;
                TextView label = new TextView(this);
                label.setText(w[0] + " · " + ww + "×" + hh + " dp · letra ×" + fs);
                label.setTextColor(0xFFB0B0B8);
                label.setTextSize(12);
                label.setPadding(0, dp(6), 0, dp(3));
                label.setTag("probe-label");
                col.addView(label);
                // Las listas solo se rellenan dentro de un contenedor de widgets, como en el launcher
                AppWidgetHostView box = new AppWidgetHostView(this);
                box.setAppWidget(0, null);
                box.setPadding(0, 0, 0, 0);
                col.addView(box, new LinearLayout.LayoutParams(dp(ww), dp(hh)));
                box.updateAppWidget(rv);
                if ("line".equals(w[0])) {
                    // Como al colocarlo en la pantalla de inicio: centrado en la hora actual
                    ListView lv = box.findViewById(R.id.wline_list);
                    Calendar now = Calendar.getInstance();
                    int slot = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) / 15;
                    if (lv != null) lv.post(() -> lv.setSelection(Math.max(0, slot - 4)));
                }
                boxes.add(new Object[]{w[0], ww + "x" + hh, box});
            }
        } catch (Throwable t) {
            Log.e("TemvraProbe", "fallo al pintar", t);
        } finally {
            Widgets.probeData = null;
            Widgets.probeFs = 0;
            Widgets.probeOpts = null;
            Widgets.probeOut = null;
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::report, 1500);
    }

    /** Recorre los textos de cada widget y apunta su tamaño y si la caja que lo contiene lo corta. */
    private void report() {
        float sd = getResources().getDisplayMetrics().scaledDensity, den = getResources().getDisplayMetrics().density;
        for (Object[] b : boxes) {
            try {
                JSONObject out = new JSONObject(), sizes = new JSONObject();
                JSONArray cut = new JSONArray(), elide = new JSONArray();
                List<TextView> tvs = new ArrayList<>();
                collect((View) b[2], tvs);
                int n = 0;
                for (TextView tv : tvs) {
                    if (!tv.isShown() || tv.length() == 0 || tv.getLayout() == null || tv.getWidth() == 0) continue;
                    n++;
                    String id = name(tv);
                    float sp = Math.round(tv.getTextSize() / sd * 10) / 10f;
                    sizes.put(id, (double) sp);
                    Layout L = tv.getLayout();
                    int aw = tv.getWidth() - tv.getTotalPaddingLeft() - tv.getTotalPaddingRight();
                    int ah = tv.getHeight() - tv.getTotalPaddingTop() - tv.getTotalPaddingBottom();
                    float lw = 0;
                    int el = 0;
                    for (int i = 0; i < L.getLineCount(); i++) { lw = Math.max(lw, L.getLineWidth(i)); el += L.getEllipsisCount(i); }
                    String why = null;
                    // Un texto con tope de líneas (maxLines) que no entra entero se acorta a propósito; en las celdas del mes sí es un fallo: ahí cada línea ya va recortada a su ancho
                    boolean capped = tv.getMaxLines() > 0 && L.getLineCount() > tv.getMaxLines() && !id.startsWith("wm_t")
                        && L.getLineBottom(tv.getMaxLines() - 1) <= ah + 2;
                    if (capped) el++;
                    if (L.getHeight() > ah + 2 && !capped) why = "alto " + Math.round(L.getHeight() / den) + ">" + Math.round(ah / den) + " dp (" + L.getLineCount() + " líneas)";
                    else if (lw > aw + 2 && el == 0 && !id.startsWith("wl_c")) why = "ancho " + Math.round(lw / den) + ">" + Math.round(aw / den) + " dp";  // las celdas de la línea de tiempo recortan a propósito
                    else if (!inPartialRow(tv)) {
                        Rect r = visibleIn(tv, (View) b[2]);
                        if (r.isEmpty()) why = "fuera de su caja";
                        else if (r.height() < tv.getHeight() - 2) why = "tapado " + Math.round((tv.getHeight() - r.height()) / den) + " dp de alto";
                        else if (r.width() < tv.getWidth() - 2) why = "tapado " + Math.round((tv.getWidth() - r.width()) / den) + " dp de ancho";
                    }
                    String txt = tv.getText().toString();
                    if (why != null) cut.put(id + " «" + (txt.length() > 14 ? txt.substring(0, 14) + "…" : txt) + "» " + sp + "sp: " + why);
                    else if (el > 0 && cut.length() + elide.length() < 40) elide.put(id);
                }
                out.put("w", b[0]).put("dp", b[1]).put("fs", fs).put("textos", n).put("cortados", cut).put("acortados", elide).put("sp", sizes);
                Log.i("TemvraProbe", out.toString());
            } catch (Exception e) {
                Log.e("TemvraProbe", "fallo al medir " + b[0], e);
            }
        }
        Log.i("TemvraProbe", "FIN");
    }

    /** La parte de una vista que queda a la vista dentro del widget: se va recortando con cada contenedor hasta llegar a la caja. */
    private static Rect visibleIn(View v, View box) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        View cur = v;
        while (cur != box && cur.getParent() instanceof View) {
            View p = (View) cur.getParent();
            r.offset(cur.getLeft() - p.getScrollX(), cur.getTop() - p.getScrollY());
            if (!r.intersect(0, 0, p.getWidth(), p.getHeight())) return new Rect();
            cur = p;
        }
        return r;
    }

    private void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        else if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out); }
    }

    private String name(View v) {
        try { return v.getId() == View.NO_ID ? "?" : getResources().getResourceEntryName(v.getId()); } catch (Exception e) { return "?"; }
    }

    /** La última fila de una lista suele asomar a medias: ese corte es de la lista, no del texto. */
    private boolean inPartialRow(View v) {
        View row = v;
        while (row.getParent() instanceof View && !(row.getParent() instanceof AbsListView)) row = (View) row.getParent();
        if (!(row.getParent() instanceof AbsListView)) return false;
        AbsListView lv = (AbsListView) row.getParent();
        return row.getTop() < 0 || row.getBottom() > lv.getHeight();
    }

    private static String hm(int min) { min = Math.max(0, Math.min(1439, min)); return String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60); }

    private static JSONObject ev(String s, String e, String t, String c, boolean done) throws Exception {
        return new JSONObject().put("s", s).put("e", e).put("t", t).put("sub", s.isEmpty() ? "Todo el día" : s + " – " + e).put("c", c).put("done", done).put("action", "event:|x|x");
    }

    /** Datos de ejemplo con la misma forma que VTAPI.widgetData(): nada del usuario. */
    private static JSONObject sample() throws Exception {
        Calendar cal = Calendar.getInstance();
        long now = System.currentTimeMillis();
        int nm = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        JSONObject days = new JSONObject();
        for (int i = -3; i <= 40; i++) {
            Calendar d = (Calendar) cal.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            String k = String.format(Locale.ROOT, "%04d-%02d-%02d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH));
            JSONArray day = new JSONArray(), blocks = new JSONArray();
            if (i == 0) {
                day.put(ev(hm(nm - 150), hm(nm - 90), "Desayuno con el equipo", "#e8a51c", true));
                day.put(ev(hm(nm - 20), hm(nm + 40), "Reunión de seguimiento del proyecto", "#3b82f6", false));
                day.put(ev(hm(nm + 15), hm(nm + 75), "Llamada con el cliente", "#2fb35b", false));
                day.put(ev(hm(nm + 120), hm(nm + 180), "Gimnasio · pierna", "#f2622e", false));
                blocks.put(ev(hm(nm - 20), hm(nm + 40), "Estudio · Java", "#8b5cf6", false));
                blocks.put(ev(hm(nm + 120), hm(nm + 180), "Gimnasio · pierna", "#f2622e", false));
            } else if (i % 2 == 0) {
                day.put(ev("09:00", "10:00", "Reunión semanal", "#3b82f6", false));
                day.put(ev("12:30", "13:30", "Comida con Marta", "#e8a51c", false));
                if (i % 4 == 0) { day.put(ev("17:00", "18:00", "Dentista", "#e5484d", false)); day.put(ev("19:00", "20:00", "Clase de guitarra", "#8b5cf6", false)); day.put(ev("21:00", "22:00", "Cena", "#2fb35b", false)); }
            } else if (i % 3 == 0) day.put(ev("", "", "Cumpleaños de Ana", "#e454a0", false));
            days.put(k, new JSONObject().put("hol", i == 5 ? "Festivo local" : "").put("day", day).put("blocks", blocks));
        }
        JSONArray notes = new JSONArray()
            .put(new JSONObject().put("id", "n1").put("t", "Ideas para la app").put("f", "").put("c", ""))
            .put(new JSONObject().put("id", "n2").put("t", "Lista de la compra de esta semana y de la que viene").put("f", "").put("c", "#2fb35b"))
            .put(new JSONObject().put("id", "n3").put("t", "Apuntes de Java").put("f", "Proyectos").put("c", ""))
            .put(new JSONObject().put("id", "n4").put("t", "Viaje a Lisboa").put("f", "").put("c", ""))
            .put(new JSONObject().put("id", "n5").put("t", "Recetas").put("f", "").put("c", ""));
        JSONObject vault = new JSONObject().put("id", "v1").put("name", "Notas").put("tc", "").put("fc", new JSONObject())
            .put("folders", new JSONArray().put("Diario").put("Proyectos")).put("notes", notes);
        JSONObject reg = new JSONObject().put("id", "r1").put("name", "Diario").put("tag", "diario").put("color", "")
            .put("rows", new JSONArray()
                .put(new JSONObject().put("d", "Martes 17 · hoy").put("t", "Hoy he avanzado con los widgets y he salido a correr media hora por la tarde."))
                .put(new JSONObject().put("d", "Lunes 16").put("t", "Día tranquilo."))
                .put(new JSONObject().put("d", "Domingo 15").put("t", "Comida familiar y paseo.")));
        JSONArray alarms = new JSONArray()
            .put(new JSONObject().put("id", "a1").put("t", "07:30").put("on", true).put("label", "Trabajo · L M X J V"))
            .put(new JSONObject().put("id", "a2").put("t", "09:15").put("on", false).put("label", "Fin de semana · S D"));
        JSONArray next = new JSONArray()
            .put(new JSONObject().put("at", now + 47 * 60_000L).put("t", "Reunión de seguimiento del proyecto").put("c", "#3b82f6").put("w", hm(nm + 47) + " – " + hm(nm + 107) + " · Sala 2").put("a", "agenda"))
            .put(new JSONObject().put("at", now + 30L * 3600_000L).put("t", "Gimnasio · pierna").put("c", "#f2622e").put("w", "Mañana · 18:00 – 19:00").put("a", "agenda"));
        return new JSONObject().put("accent", "#C6C4F4").put("weekStart", 1).put("curVault", "v1").put("vaults", new JSONArray().put(vault))
            .put("regs", new JSONArray().put(reg)).put("alarms", alarms)
            .put("timer", new JSONObject().put("name", "Temporizador de estudio").put("running", false).put("endAt", 0).put("remain", 90_000).put("period", 90_000).put("lap", 2).put("loop", true))
            .put("alarm", new JSONObject().put("at", now + 3600_000L).put("time", "07:30").put("sub", "Trabajo · Miércoles"))
            .put("days", days).put("pulse", new JSONObject().put("laser", "#FF3B43").put("next", next));
    }
}
