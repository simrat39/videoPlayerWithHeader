package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource.BaseFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource.RequestProperties;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

public class VideoPlayerPlugin implements MethodCallHandler, FlutterPlugin {

    private Map<Long, VideoPlayer> videoPlayers;
    private BinaryMessenger messenger;
    private TextureRegistry textureRegistry;
    private Context context;
    private FlutterAssets assets;

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding binding) {
        this.videoPlayers = new HashMap<>();
        this.messenger = binding.getBinaryMessenger();
        this.textureRegistry = binding.getTextureRegistry();
        this.context = binding.getApplicationContext();
        this.assets = binding.getFlutterAssets();

        final MethodChannel methodChannel = new MethodChannel(binding.getBinaryMessenger(), "flutter.io/videoPlayer");
        methodChannel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {
        this.onDestroy();

        this.videoPlayers = null;
        this.messenger = null;
        this.textureRegistry = null;
        this.context = null;
        this.assets = null;
    }

    void onDestroy() {
        // The whole FlutterView is being destroyed. Here we release resources acquired for all instances
        // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
        // be replaced with just asserting that videoPlayers.isEmpty().
        // https://github.com/flutter/flutter/issues/20989 tracks this.
        for (VideoPlayer player : videoPlayers.values()) {
            player.dispose();
        }
        videoPlayers.clear();
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        TextureRegistry textures = textureRegistry;
        if (textures == null) {
            result.error("no_activity", "video_player_header plugin requires a foreground activity", null);
            return;
        }
        switch (call.method) {
            case "init":
                for (VideoPlayer player : videoPlayers.values()) {
                    player.dispose();
                }
                videoPlayers.clear();
                break;
            case "create": {
                TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
                EventChannel eventChannel =
                        new EventChannel(
                                messenger, "flutter.io/videoPlayer/videoEvents" + handle.id());

                VideoPlayer player;
                if (call.argument("asset") != null) {
                    String assetLookupKey;
                    if (call.argument("package") != null) {
                        assetLookupKey =
                                assets.getAssetFilePathByName(
                                        call.argument("asset"), call.argument("package"));
                    } else {
                        assetLookupKey = assets.getAssetFilePathByName(call.argument("asset"));
                    }
                    player =
                            new VideoPlayer(
                                    context,
                                    eventChannel,
                                    handle,
                                    "asset:///" + assetLookupKey,
                                    null,
                                    result);
                    videoPlayers.put(handle.id(), player);
                } else {
                    player =
                            new VideoPlayer(
                                    context,
                                    eventChannel,
                                    handle,
                                    call.argument("uri"),
                                    call.argument("headers"),
                                    result);
                    videoPlayers.put(handle.id(), player);
                }
                break;
            }
            default: {
                long textureId = ((Number) Objects.requireNonNull(call.argument("textureId"))).longValue();
                VideoPlayer player = videoPlayers.get(textureId);
                if (player == null) {
                    result.error(
                            "Unknown textureId",
                            "No video player associated with texture id " + textureId,
                            null);
                    return;
                }
                onMethodCall(call, result, textureId, player);
                break;
            }
        }
    }

    private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
        switch (call.method) {
            case "setLooping":
                player.setLooping(call.argument("looping"));
                result.success(null);
                break;
            case "setVolume":
                player.setVolume(call.argument("volume"));
                result.success(null);
                break;
            case "play":
                player.play();
                result.success(null);
                break;
            case "pause":
                player.pause();
                result.success(null);
                break;
            case "seekTo":
                int location = ((Number) Objects.requireNonNull(call.argument("location"))).intValue();
                player.seekTo(location);
                result.success(null);
                break;
            case "setPlaybackSpeed":
                double speed = call.argument("speed");
                player.setPlaybackSpeed(speed);
                result.success(null);
                break;
            case "getPlaybackSpeed":
                result.success(player.getPlaybackSpeed());
                break;
            case "position":
                result.success(player.getPosition());
                break;
            case "dispose":
                player.dispose();
                videoPlayers.remove(textureId);
                result.success(null);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    // near copy and paste from final class DefaultHttpDataSourceFactory
    private static class VideoPlayerHttpDataSourceFactory extends BaseFactory {
        private final String userAgent;
        private final @Nullable
        TransferListener listener;
        private final int connectTimeoutMillis;
        private final int readTimeoutMillis;
        private final boolean allowCrossProtocolRedirects;
        private final Map<String, String> headers;

        public VideoPlayerHttpDataSourceFactory(
                String userAgent,
                @Nullable TransferListener listener,
                int connectTimeoutMillis,
                int readTimeoutMillis,
                boolean allowCrossProtocolRedirects,
                Map<String, String> headers) {
            this.userAgent = userAgent;
            this.listener = listener;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
            this.headers = headers;
        }

        @Override
        protected DefaultHttpDataSource createDataSourceInternal(
                RequestProperties defaultRequestProperties) {
            if (this.headers != null) {
                if (defaultRequestProperties == null) {
                    defaultRequestProperties = new RequestProperties();
                }
                for (Map.Entry<String, String> header : this.headers.entrySet()) {
                    defaultRequestProperties.set(header.getKey(), header.getValue());
                }
            }
            DefaultHttpDataSource dataSource =
                    new DefaultHttpDataSource(
                            userAgent,
                            connectTimeoutMillis,
                            readTimeoutMillis,
                            allowCrossProtocolRedirects,
                            defaultRequestProperties);
            if (listener != null) {
                dataSource.addTransferListener(listener);
            }
            return dataSource;
        }
    }

    private static class VideoPlayer {

        private final SimpleExoPlayer exoPlayer;
        private final TextureRegistry.SurfaceTextureEntry textureEntry;
        private final QueuingEventSink eventSink = new QueuingEventSink();
        private final EventChannel eventChannel;
        private Surface surface;
        private boolean isInitialized = false;

        VideoPlayer(
                Context context,
                EventChannel eventChannel,
                TextureRegistry.SurfaceTextureEntry textureEntry,
                String dataSource,
                Map<String, String> headers,
                Result result) {
            this.eventChannel = eventChannel;
            this.textureEntry = textureEntry;

            exoPlayer = new SimpleExoPlayer.Builder(context).setTrackSelector(new DefaultTrackSelector(context)).build();

            Uri uri = Uri.parse(dataSource);

            DataSource.Factory dataSourceFactory;
            if (Objects.requireNonNull(uri.getScheme()).equals("asset") || uri.getScheme().equals("file")) {
                dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
            } else {
                dataSourceFactory =
                        new VideoPlayerHttpDataSourceFactory(
                                "Mozilla/5.0 (X11; Linux x86_64; rv:84.0) Gecko/20100101 Firefox/84.0",
                                null,
                                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                                true,
                                headers);
            }

            MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, context);
            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();

            setupVideoPlayer(eventChannel, textureEntry, result, context);
        }

        @SuppressWarnings("deprecation")
        private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                exoPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.CONTENT_TYPE_MOVIE)
                                .build(), true);
            } else exoPlayer.setAudioStreamType(C.STREAM_TYPE_MUSIC);
        }

        private MediaSource buildMediaSource(
                Uri uri, DataSource.Factory mediaDataSourceFactory, Context context) {
            int type = Util.inferContentType(uri.getLastPathSegment());
            switch (type) {
                case C.TYPE_SS:
                    return new SsMediaSource.Factory(
                            new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                            new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
                            .createMediaSource(MediaItem.fromUri(uri));
                case C.TYPE_DASH:
                    return new DashMediaSource.Factory(
                            new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                            new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
                            .createMediaSource(MediaItem.fromUri(uri));
                case C.TYPE_HLS:
                    return new HlsMediaSource.Factory(mediaDataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(uri));
                case C.TYPE_OTHER:
                    return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(uri));
                default: {
                    throw new IllegalStateException("Unsupported type: " + type);
                }
            }
        }

        private void setupVideoPlayer(
                EventChannel eventChannel,
                TextureRegistry.SurfaceTextureEntry textureEntry,
                Result result,
                Context context) {

            eventChannel.setStreamHandler(
                    new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object o, EventChannel.EventSink sink) {
                            eventSink.setDelegate(sink);
                        }

                        @Override
                        public void onCancel(Object o) {
                            eventSink.setDelegate(null);
                        }
                    });

            surface = new Surface(textureEntry.surfaceTexture());
            exoPlayer.setVideoSurface(surface);
            setAudioAttributes(exoPlayer);

            exoPlayer.addListener(
                    new Player.EventListener() {
                        @Override
                        public void onPlaybackStateChanged(final int playbackState) {
                            Player.EventListener.super.onPlaybackStateChanged(playbackState);
                            if (playbackState == Player.STATE_BUFFERING) {
                                Map<String, Object> event = new HashMap<>();
                                event.put("event", "bufferingUpdate");
                                List<Integer> range = Arrays.asList(0, exoPlayer.getBufferedPercentage());
                                // iOS supports a list of buffered ranges, so here is a list with a single range.
                                event.put("values", Collections.singletonList(range));
                                event.put("event", "bufferingStart");
                                eventSink.success(event);
                            } else if (playbackState == Player.STATE_READY) {
                                if (isInitialized) {
                                    Map<String, Object> event = new HashMap<>();
                                    event.put("event", "bufferingEnd");
                                    eventSink.success(event);
                                } else {
                                    isInitialized = true;
                                    sendInitialized();
                                }
                            }
                        }

                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            if (isPlaying) {
                                Map<String, Object> event = new HashMap<>();
                                event.put("event", "played");
                                eventSink.success(event);
                            } else {
                                Map<String, Object> event = new HashMap<>();
                                event.put("event", "paused");
                                eventSink.success(event);
                            }
                        }

                        @Override
                        public void onPlayerError(final ExoPlaybackException error) {
                            Player.EventListener.super.onPlayerError(error);
                            eventSink.error("VideoError", "Video player had error " + error, null);
                        }
                    });

            MediaSessionCompat mediaSession = new MediaSessionCompat(context, "packageName");
            MediaSessionConnector mediaSessionConnector = new MediaSessionConnector(mediaSession);
            mediaSessionConnector.setPlayer(exoPlayer);
            mediaSession.setActive(true);

            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", textureEntry.id());
            result.success(reply);
        }

        void play() {
            exoPlayer.setPlayWhenReady(true);
        }

        void pause() {
            exoPlayer.setPlayWhenReady(false);
        }

        void setLooping(boolean value) {
            exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
        }

        void setPlaybackSpeed(double speed) {
            exoPlayer.setPlaybackParameters(new PlaybackParameters((float) speed));
        }

        float getPlaybackSpeed() {
            return exoPlayer.getPlaybackParameters().speed;
        }

        void setVolume(double value) {
            float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
            exoPlayer.setVolume(bracketedValue);
        }

        void seekTo(int location) {
            exoPlayer.seekTo(location);
        }

        long getPosition() {
            return exoPlayer.getCurrentPosition();
        }

        private void sendInitialized() {
            if (isInitialized) {
                Map<String, Object> event = new HashMap<>();
                event.put("event", "initialized");
                event.put("duration", exoPlayer.getDuration());
                if (exoPlayer.getVideoFormat() != null) {
                    event.put("width", exoPlayer.getVideoFormat().width);
                    event.put("height", exoPlayer.getVideoFormat().height);
                }
                eventSink.success(event);
            }
        }

        void dispose() {
            if (isInitialized) {
                exoPlayer.stop();
            }
            textureEntry.release();
            eventChannel.setStreamHandler(null);
            if (surface != null) {
                surface.release();
            }
            if (exoPlayer != null) {
                exoPlayer.release();
            }
        }
    }
}
