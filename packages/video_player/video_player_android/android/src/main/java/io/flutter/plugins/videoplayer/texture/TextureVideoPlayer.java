// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import io.flutter.plugins.videoplayer.ExoPlayerEventListener;
import io.flutter.plugins.videoplayer.VideoAsset;
import io.flutter.plugins.videoplayer.VideoPlayer;
import io.flutter.plugins.videoplayer.VideoPlayerCallbacks;
import io.flutter.plugins.videoplayer.VideoPlayerOptions;
import io.flutter.view.TextureRegistry.SurfaceProducer;

/**
 * A subclass of {@link VideoPlayer} that adds functionality related to texture view as a way of
 * displaying the video in the app.
 *
 * <p>It manages the lifecycle of the texture and ensures that the video is properly displayed on
 * the texture.
 */
public final class TextureVideoPlayer extends VideoPlayer implements SurfaceProducer.Callback {
  // True when the ExoPlayer instance has a null surface.
  private boolean needsSurface = true;

  // Disha fork: true when onSurfaceCleanup stopped a prepared player, so that
  // onSurfaceAvailable prepares it again.
  private boolean stoppedForSurfaceCleanup = false;

  /**
   * Creates a texture video player.
   *
   * @param context application context.
   * @param events event callbacks.
   * @param surfaceProducer produces a texture to render to.
   * @param asset asset to play.
   * @param options options for playback.
   * @return a video player instance.
   */
  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @NonNull
  public static TextureVideoPlayer create(
      @NonNull Context context,
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull VideoAsset asset,
      @NonNull VideoPlayerOptions options) {
    return new TextureVideoPlayer(
        events,
        surfaceProducer,
        asset.getMediaItem(),
        options,
        () -> {
          // Disha fork: let ExoPlayer retry with the next available decoder (e.g. a
          // software one) when the preferred hardware decoder fails to initialize.
          ExoPlayer.Builder builder =
              new ExoPlayer.Builder(
                  context, new DefaultRenderersFactory(context).setEnableDecoderFallback(true));
          if (options.backBufferDurationMs != null) {
            if (options.backBufferDurationMs < 0) {
              throw new IllegalArgumentException("backBufferDurationMs must be at least 0");
            }
            if (options.backBufferDurationMs > 0) {
              // Clamp the value to ensure it fits within the int range expected by
              // DefaultLoadControl.
              int backBufferInt =
                  (int) Math.min(options.backBufferDurationMs.longValue(), Integer.MAX_VALUE);
              DefaultLoadControl loadControl =
                  new DefaultLoadControl.Builder()
                      .setBackBuffer(backBufferInt, /* retainBackBufferFromKeyframe= */ true)
                      .build();
              builder.setLoadControl(loadControl);
            }
          }
          androidx.media3.exoplayer.trackselection.DefaultTrackSelector trackSelector =
              new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context);
          builder
              .setTrackSelector(trackSelector)
              .setMediaSourceFactory(asset.getMediaSourceFactory(context));
          ExoPlayer exoPlayer = builder.build();
          // Disha fork: attach the surface before VideoPlayer's constructor calls prepare().
          // Otherwise a fast prepare (e.g. a local file) can start the decoder on a placeholder
          // surface, and some OMX decoders reject the later switch to the ImageReader surface
          // ("new surface holds onto more buffers than planned for"), failing playback with
          // IllegalArgumentException from MediaCodec.setOutputSurface (Sentry DISHA-91S).
          // The constructor sets the same Surface again, which ExoPlayer treats as a no-op.
          exoPlayer.setVideoSurface(surfaceProducer.getSurface());
          return exoPlayer;
        });
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @VisibleForTesting
  public TextureVideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    super(events, mediaItem, options, surfaceProducer, exoPlayerProvider);

    surfaceProducer.setCallback(this);

    Surface surface = surfaceProducer.getSurface();
    this.exoPlayer.setVideoSurface(surface);
    needsSurface = surface == null;
  }

  @NonNull
  @Override
  protected ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer, @Nullable SurfaceProducer surfaceProducer) {
    if (surfaceProducer == null) {
      throw new IllegalArgumentException(
          "surfaceProducer cannot be null to create an ExoPlayerEventListener for"
              + " TextureVideoPlayer.");
    }
    boolean surfaceProducerHandlesCropAndRotation = surfaceProducer.handlesCropAndRotation();
    return new TextureExoPlayerEventListener(
        exoPlayer, videoPlayerEvents, surfaceProducerHandlesCropAndRotation);
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceAvailable() {
    if (needsSurface) {
      // TextureVideoPlayer must always set a surfaceProducer.
      assert surfaceProducer != null;
      exoPlayer.setVideoSurface(surfaceProducer.getSurface());
      needsSurface = false;
    }
    if (stoppedForSurfaceCleanup) {
      stoppedForSurfaceCleanup = false;
      // stop() kept the media item, the position and playWhenReady, so this resumes where
      // playback was when the surface went away.
      exoPlayer.prepare();
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceCleanup() {
    // Disha fork: release the decoders before the surface goes away. Flutter destroys the
    // surface when the backgrounded app is asked to trim memory; detaching it from a live
    // MediaCodec makes the codec switch to a placeholder surface, which fails on many
    // low-end decoders with IllegalStateException / CodecException and leaves the player in
    // a permanent error state (Sentry DISHA-8YY, DISHA-8Z0, DISHA-90E). stop() keeps the
    // media item, the position and playWhenReady; onSurfaceAvailable prepares again.
    // A player that is already idle (never prepared, or failed) is left alone so that a
    // reported error is not silently retried.
    if (exoPlayer.getPlaybackState() != Player.STATE_IDLE) {
      exoPlayer.stop();
      stoppedForSurfaceCleanup = true;
    }
    exoPlayer.setVideoSurface(null);
    needsSurface = true;
  }

  public void dispose() {
    // Super must be called first to ensure the player is released before the surface.
    super.dispose();

    // TextureVideoPlayer must always set a surfaceProducer.
    assert surfaceProducer != null;
    surfaceProducer.release();
  }
}
