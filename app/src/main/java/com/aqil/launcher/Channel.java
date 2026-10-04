package com.aqil.launcher;

final class Channel {
    final String name, url, group, logo;
    /** Pengepala HTTP pilihan dari M3U (#EXTVLCOPT / |User-Agent=...). */
    String ua, referer, origin;
    /** DRM dari #KODIPROP: "clearkey" / "com.widevine.alpha"; key = "kid:key" atau URL lesen. */
    String drmType, drmKey;
    /** null = belum disemak, TRUE = berfungsi, FALSE = mati. */
    volatile Boolean alive;

    Channel(String name, String url, String group, String logo) {
        this.name = name;
        this.url = url;
        this.group = group == null ? "" : group;
        this.logo = logo == null ? "" : logo;
    }
}
