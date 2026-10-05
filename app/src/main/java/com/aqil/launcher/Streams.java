package com.aqil.launcher;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bina ExoPlayer & sumber media untuk saluran IPTV (dikongsi oleh pratonton dan skrin penuh). */
@OptIn(markerClass = UnstableApi.class)
final class Streams {
    private Streams() {}

    static ExoPlayer player(Context c, boolean preview) {
        return player(c, preview, false);
    }

    /**
     * @param tunnel tunneling video: bingkai dihantar terus dari dekoder ke paparan oleh cip TV (kurang kerja CPU,
     *               audio-video lebih sekata). ExoPlayer hanya mengaktifkannya jika dekoder audio & video menyokong.
     */
    static ExoPlayer player(Context c, boolean preview, boolean tunnel) {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        // Penimbal (anti-lag): mod Lancar mula main selepas 2.5s ditimbal dan, selepas tersekat, tunggu 6s
        // supaya tidak tersekat-sekat berulang. Had 48MB supaya selamat untuk memori TV box.
        boolean smooth = !preview && Store.smooth(c);
        DefaultLoadControl lc = preview
                ? new DefaultLoadControl.Builder().setBufferDurationsMs(4000, 15000, 800, 1500).build()
                : smooth
                ? new DefaultLoadControl.Builder().setBufferDurationsMs(30000, 90000, 2500, 6000)
                        .setTargetBufferBytes(48 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
                : new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 1000, 2500).build();
        // Kualiti: "Pintar" (lalai) mula di HD (anggaran lebar jalur awal tinggi) tetapi boleh turun bila Internet
        // perlahan supaya tak tersekat; "Tertinggi" paksa resolusi tertinggi. Pratonton kecil = SD sahaja.
        DefaultTrackSelector ts = new DefaultTrackSelector(c);
        DefaultTrackSelector.Parameters.Builder pb = ts.buildUponParameters();
        if (preview) pb.setMaxVideoSizeSd();
        else if (Store.maxQuality(c)) pb.setForceHighestSupportedBitrate(true);
        if (tunnel && !preview) pb.setTunnelingEnabled(true);
        ts.setParameters(pb);
        // anggaran lebar jalur awal tinggi supaya mod Auto pun terus pilih HD, bukan mula dari kualiti terendah
        DefaultBandwidthMeter bw = new DefaultBandwidthMeter.Builder(c).setInitialBitrateEstimate(preview ? 2_000_000L : 20_000_000L).build();
        ExoPlayer p = new ExoPlayer.Builder(c, rf).setLoadControl(lc).setTrackSelector(ts).setBandwidthMeter(bw).build();
        p.setPlayWhenReady(true);
        if (!preview) meter = bw;
        return p;
    }

    /** Pengukur lebar jalur pemain skrin penuh terakhir (untuk paparan "Mbps" dalam info saluran). */
    static volatile DefaultBandwidthMeter meter;

    static long bitrateEstimate() {
        DefaultBandwidthMeter m = meter;
        return m == null ? 0 : m.getBitrateEstimate();
    }

    static MediaSource source(Context ctx, Channel c, String forceMime) {
        Map<String, String> headers = new HashMap<>();
        if (c.referer != null) headers.put("Referer", c.referer);
        if (c.origin != null) headers.put("Origin", c.origin);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(c.ua != null ? c.ua : Hub.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(ctx).setDataSourceFactory(http);

        MediaItem.Builder mb = new MediaItem.Builder().setUri(c.url);
        String low = c.url.toLowerCase(Locale.ROOT);
        String mime = forceMime;
        if (mime == null) {
            if (low.contains("m3u8")) mime = MimeTypes.APPLICATION_M3U8;
            else if (low.contains(".mpd")) mime = MimeTypes.APPLICATION_MPD;
            else if (c.drmType != null) mime = MimeTypes.APPLICATION_MPD;
        }
        if (mime != null) mb.setMimeType(mime);

        if (c.drmType != null && c.drmKey != null) {
            String type = c.drmType.toLowerCase(Locale.ROOT);
            if (type.contains("clearkey") && !c.drmKey.startsWith("http")) {
                final DrmSessionManager mgr = new DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(new LocalMediaDrmCallback(clearKeyJson(c.drmKey).getBytes()));
                msf.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                    @Override
                    public DrmSessionManager get(MediaItem item) {
                        return mgr;
                    }
                });
            } else {
                java.util.UUID uuid = type.contains("widevine") ? C.WIDEVINE_UUID : type.contains("playready") ? C.PLAYREADY_UUID : C.CLEARKEY_UUID;
                mb.setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(uuid).setLicenseUri(c.drmKey).build());
            }
        }
        return msf.createMediaSource(mb.build());
    }

    /** "kid:key[,kid:key]" (hex) → JSON ClearKey yang ExoPlayer faham. */
    static String clearKeyJson(String spec) {
        if (spec.trim().startsWith("{")) return spec;
        StringBuilder sb = new StringBuilder("{\"keys\":[");
        boolean first = true;
        for (String pair : spec.split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length != 2) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"kty\":\"oct\",\"kid\":\"").append(b64(kv[0])).append("\",\"k\":\"").append(b64(kv[1])).append("\"}");
        }
        return sb.append("],\"type\":\"temporary\"}").toString();
    }

    private static String b64(String hex) {
        hex = hex.trim();
        if (!hex.matches("[0-9a-fA-F]+")) return hex;
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
