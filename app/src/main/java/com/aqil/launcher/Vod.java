package com.aqil.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Filem & Siri (VOD) dari akaun Xtream Codes: kategori, senarai, maklumat dan episod. */
final class Vod {
    static final String MOVIE = "vod", SERIES = "series";
    static final ExecutorService IO = Executors.newFixedThreadPool(2);

    /** Satu filem / siri dalam grid. */
    static final class Item {
        String kind, id, name, icon, rating = "", ext = "mp4", cat;
        /** Ada terus dalam senarai siri (get_series); filem biasanya perlu get_vod_info. */
        String plot = "", year = "", genre = "", backdrop = "";
    }

    /** Maklumat penuh (filem atau siri). */
    static final class Detail {
        String plot = "", genre = "", cast = "", director = "", year = "", duration = "", rating = "", backdrop = "", poster = "";
        String ext = "mp4";
        /** Siri: nombor musim → episod (tersusun). */
        final Map<Integer, List<Ep>> seasons = new java.util.TreeMap<>();
    }

    static final class Ep {
        String id, title, ext = "mp4", plot = "", image = "", duration = "";
        int season, num;
    }

    private static final Map<String, List<String[]>> cats = new HashMap<>();
    private static final Map<String, List<Item>> lists = new HashMap<>();

    private Vod() {}

    static void clearCache() {
        synchronized (cats) {
            cats.clear();
        }
        synchronized (details) {
            details.clear();
        }
        synchronized (lists) {
            lists.clear();
        }
    }

    /** Kategori [id, nama] untuk MOVIE / SERIES. */
    static List<String[]> categories(Context c, String kind) throws Exception {
        synchronized (cats) {
            if (cats.containsKey(kind)) return cats.get(kind);
        }
        String[] x = Store.xtream(c);
        if (x == null) throw new Exception("Log masuk akaun IPTV dahulu");
        JSONArray a = new JSONArray(Xtream.get(Xtream.api(x, kind.equals(MOVIE) ? "get_vod_categories" : "get_series_categories")));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new String[]{o.optString("category_id"), o.optString("category_name")});
        }
        synchronized (cats) {
            cats.put(kind, out);
        }
        return out;
    }

    /** Senarai filem/siri dalam kategori (catId null = semua, untuk carian). Terbaru dulu. */
    static List<Item> items(Context c, String kind, String catId) throws Exception {
        String key = kind + "|" + catId;
        synchronized (lists) {
            if (lists.containsKey(key)) return lists.get(key);
        }
        String[] x = Store.xtream(c);
        if (x == null) throw new Exception("Log masuk akaun IPTV dahulu");
        String url = Xtream.api(x, kind.equals(MOVIE) ? "get_vod_streams" : "get_series")
                + (catId == null ? "" : "&category_id=" + java.net.URLEncoder.encode(catId, "UTF-8"));
        JSONArray a = new JSONArray(Xtream.get(url));
        List<Item> out = new ArrayList<>(a.length());
        final Map<Item, Long> added = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Item it = new Item();
            it.kind = kind;
            it.id = o.optString(kind.equals(MOVIE) ? "stream_id" : "series_id");
            if (it.id.isEmpty()) continue;
            it.name = o.optString("name", "").trim();
            it.icon = o.optString(kind.equals(MOVIE) ? "stream_icon" : "cover", "");
            it.rating = o.optString("rating", "");
            if (it.rating.equals("0") || it.rating.equals("null")) it.rating = "";
            it.ext = o.optString("container_extension", "mp4");
            it.cat = o.optString("category_id");
            it.plot = clean(o.optString("plot", ""));
            it.genre = clean(o.optString("genre", ""));
            String rel = clean(o.optString("releaseDate", o.optString("release_date", o.optString("year", ""))));
            it.year = rel.length() >= 4 ? rel.substring(0, 4) : rel;
            JSONArray bd = o.optJSONArray("backdrop_path");
            if (bd != null && bd.length() > 0) it.backdrop = clean(bd.optString(0, ""));
            long ad = 0;
            try {
                ad = Long.parseLong(o.optString(kind.equals(MOVIE) ? "added" : "last_modified", "0"));
            } catch (NumberFormatException ignored) {
            }
            added.put(it, ad);
            out.add(it);
        }
        Collections.sort(out, (p, q) -> Long.compare(added.get(q), added.get(p)));
        synchronized (lists) {
            if (lists.size() > 30) lists.clear(); // had memori
            lists.put(key, out);
        }
        return out;
    }

    static List<Item> search(Context c, String kind, String q) throws Exception {
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<Item> out = new ArrayList<>();
        for (Item it : items(c, kind, null)) if (it.name.toLowerCase(Locale.ROOT).contains(s)) out.add(it);
        return out;
    }

    /** Butiran yang sudah dimuat (atau null) – untuk paparan serta-merta tanpa rangkaian. */
    static Detail cachedDetail(Item it) {
        synchronized (details) {
            return details.get(it.kind + "|" + it.id);
        }
    }

    /** Kategori yang sudah dimuat (atau null). */
    static List<String[]> cachedCategories(String kind) {
        synchronized (cats) {
            return cats.get(kind);
        }
    }

    private static final Map<String, Detail> details = new java.util.LinkedHashMap<String, Detail>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Detail> e) {
            return size() > 80;
        }
    };

    static Detail detail(Context c, Item it) throws Exception {
        Detail hit = cachedDetail(it);
        if (hit != null) return hit;
        Detail d = loadDetail(c, it);
        synchronized (details) {
            details.put(it.kind + "|" + it.id, d);
        }
        return d;
    }

    private static Detail loadDetail(Context c, Item it) throws Exception {
        String[] x = Store.xtream(c);
        if (x == null) throw new Exception("Log masuk akaun IPTV dahulu");
        Detail d = new Detail();
        d.poster = it.icon;
        d.ext = it.ext;
        if (it.kind.equals(MOVIE)) {
            JSONObject o = new JSONObject(Xtream.get(Xtream.api(x, "get_vod_info") + "&vod_id=" + it.id));
            JSONObject info = o.optJSONObject("info");
            if (info != null) fill(d, info);
            JSONObject md = o.optJSONObject("movie_data");
            if (md != null && !md.optString("container_extension").isEmpty()) d.ext = md.optString("container_extension");
        } else {
            JSONObject o = new JSONObject(Xtream.get(Xtream.api(x, "get_series_info") + "&series_id=" + it.id));
            JSONObject info = o.optJSONObject("info");
            if (info != null) fill(d, info);
            Object eps = o.opt("episodes");
            if (eps instanceof JSONObject) {
                JSONObject eo = (JSONObject) eps;
                for (Iterator<String> k = eo.keys(); k.hasNext(); ) {
                    String season = k.next();
                    JSONArray arr = eo.optJSONArray(season);
                    if (arr != null) addEpisodes(d, arr, parseInt(season, 1));
                }
            } else if (eps instanceof JSONArray) { // sesetengah panel: senarai senarai
                JSONArray ea = (JSONArray) eps;
                for (int i = 0; i < ea.length(); i++) {
                    JSONArray arr = ea.optJSONArray(i);
                    if (arr != null) addEpisodes(d, arr, i + 1);
                }
            }
        }
        if (d.poster.isEmpty()) d.poster = it.icon;
        return d;
    }

    private static void addEpisodes(Detail d, JSONArray arr, int fallbackSeason) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            Ep ep = new Ep();
            ep.id = e.optString("id");
            if (ep.id.isEmpty()) continue;
            ep.season = parseInt(e.optString("season"), fallbackSeason);
            ep.num = parseInt(e.optString("episode_num"), i + 1);
            ep.title = e.optString("title", "Episod " + ep.num);
            ep.ext = e.optString("container_extension", "mp4");
            JSONObject info = e.optJSONObject("info");
            if (info != null) {
                ep.plot = info.optString("plot", "");
                ep.image = info.optString("movie_image", "");
                ep.duration = info.optString("duration", "");
            }
            List<Ep> l = d.seasons.get(ep.season);
            if (l == null) d.seasons.put(ep.season, l = new ArrayList<>());
            l.add(ep);
        }
        for (List<Ep> l : d.seasons.values()) Collections.sort(l, (p, q) -> Integer.compare(p.num, q.num));
    }

    private static void fill(Detail d, JSONObject info) {
        d.plot = clean(info.optString("plot", info.optString("description", "")));
        d.genre = clean(info.optString("genre", ""));
        d.cast = clean(info.optString("cast", info.optString("actors", "")));
        d.director = clean(info.optString("director", ""));
        String rel = clean(info.optString("releasedate", info.optString("releaseDate", "")));
        d.year = rel.length() >= 4 ? rel.substring(0, 4) : rel;
        d.duration = clean(info.optString("duration", ""));
        d.rating = clean(info.optString("rating", ""));
        if (d.rating.equals("0")) d.rating = "";
        String img = clean(info.optString("movie_image", info.optString("cover", "")));
        if (!img.isEmpty()) d.poster = img;
        JSONArray bd = info.optJSONArray("backdrop_path");
        if (bd != null && bd.length() > 0) d.backdrop = bd.optString(0, "");
    }

    private static String clean(String s) {
        return s == null || s.equals("null") ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static String movieUrl(Context c, Item it, String ext) {
        String[] x = Store.xtream(c);
        return x == null ? null : x[0] + "/movie/" + x[1] + "/" + x[2] + "/" + it.id + "." + ext;
    }

    static String episodeUrl(Context c, Ep e) {
        String[] x = Store.xtream(c);
        return x == null ? null : x[0] + "/series/" + x[1] + "/" + x[2] + "/" + e.id + "." + e.ext;
    }
}
