package com.kaltura.playkit.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelections;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.PlayerState;
import com.kaltura.playkit.utils.EventLogger;


/**
 * Created by anton.afanasiev on 31/10/2016.
 */
public class ExoPlayerWrapper implements PlayerEngine, ExoPlayer.EventListener, TrackSelector.EventListener<MappingTrackSelector.MappedTrackInfo> {
    private static final String TAG = ExoPlayerWrapper.class.getSimpleName();
    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();

    private EventLogger eventLogger; //TODO probably should be changed/wrapped by our own eventLogger? anyway for now it is ok.
    private PlayerController.EventTrigger eventTrigger;

    private Context context;
    private SimpleExoPlayer player;
    private SimpleExoPlayerView exoPlayerView;

    private DataSource.Factory mediaDataSourceFactory;
    private Handler mainHandler = new Handler();
    private boolean playerNeedsSource;

    private boolean isSeeking = false;
    private boolean firstPlay;

    private PlayerEvent currentEvent;
    private PlayerState currentState = PlayerState.IDLE;
    private PlayerController.StateChangedTrigger stateChangedTrigger;


    public ExoPlayerWrapper(Context context) {
        this.context = context;
        mediaDataSourceFactory = buildDataSourceFactory(true);
        exoPlayerView = new SimpleExoPlayerView(context);
        exoPlayerView.setUseController(false);
    }

    private void initializePlayer(final boolean shouldAutoplay) {
        eventLogger = new EventLogger();
        DefaultTrackSelector trackSelector = initializeTrackSelector();

        player = ExoPlayerFactory.newSimpleInstance(context, trackSelector, new DefaultLoadControl(), null, false); // TODO check if we need DRM Session manager.
        setPlayerListeners();
        exoPlayerView.setPlayer(player);
        player.setPlayWhenReady(shouldAutoplay);
        playerNeedsSource = true;
    }

    private void setPlayerListeners() {
        if (player != null) {
            player.addListener(this);
            player.addListener(eventLogger);
            player.setVideoDebugListener(eventLogger);
            player.setAudioDebugListener(eventLogger);
            player.setId3Output(eventLogger);
        }
    }

    private DefaultTrackSelector initializeTrackSelector() {
        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveVideoTrackSelection.Factory(BANDWIDTH_METER);
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(mainHandler, videoTrackSelectionFactory);
        trackSelector.addListener(this);
        trackSelector.addListener(eventLogger);

        return trackSelector;
    }

    private void preparePlayer(Uri mediaSourceUri) {
        firstPlay = true;
        changeState(PlayerState.LOADING);
        MediaSource mediaSource = buildMediaSource(mediaSourceUri, null);
        player.prepare(mediaSource);
        playerNeedsSource = false;
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {

        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension
                : uri.getLastPathSegment());
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mainHandler, eventLogger);
            case C.TYPE_DASH:
                return new DashMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mainHandler, eventLogger);
            case C.TYPE_HLS:
                return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, eventLogger);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(),
                        mainHandler, eventLogger);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a listener to the new
     *                          DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultDataSourceFactory(context, useBandwidthMeter ? BANDWIDTH_METER : null,
                buildHttpDataSourceFactory(useBandwidthMeter));
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a listener to the new
     *                          DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultHttpDataSourceFactory(Util.getUserAgent(context, "PlayKit"), useBandwidthMeter ? BANDWIDTH_METER : null);
    }

    private void changeState(PlayerState newState) {
        if (newState.equals(currentState)) {
            return;
        }
        this.currentState = newState;
        stateChangedTrigger.triggerStateChanged(currentState);
    }

    private void sendEvent(PlayerEvent newEvent) {
        if(newEvent.equals(currentEvent)){
            return;
        }

        currentEvent = newEvent;
        if (eventTrigger != null) {
            eventTrigger.triggerEvent(currentEvent);
        }
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        Log.e(TAG, "onLoadingChanged. isLoading => " + isLoading);
        if(isLoading){
            sendEvent(PlayerEvent.LOADED_METADATA);
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        switch (playbackState) {
            case ExoPlayer.STATE_IDLE:
                Log.e(TAG, "onPlayerStateChanged. IDLE. playWhenReady => " + playWhenReady);
                changeState(PlayerState.IDLE);
                if (isSeeking) {
                    isSeeking = false;
                }
                break;
            case ExoPlayer.STATE_BUFFERING:
                Log.e(TAG, "onPlayerStateChanged. BUFFERING. playWhenReady => " + playWhenReady);
                changeState(PlayerState.BUFFERING);
                break;
            case ExoPlayer.STATE_READY:
                Log.e(TAG, "onPlayerStateChanged. READY. playWhenReady => " + playWhenReady);

                sendEvent(PlayerEvent.CAN_PLAY);
                //launch Playing event
                if(firstPlay && playWhenReady){
                    firstPlay = false;
                    sendEvent(PlayerEvent.PLAYING);
                }

                if(isSeeking){
                    isSeeking = false;
                    sendEvent(PlayerEvent.SEEKED);
                }

                changeState(PlayerState.READY);
                break;
            case ExoPlayer.STATE_ENDED:
                Log.e(TAG, "onPlayerStateChanged. ENDED. playWhenReady => " + playWhenReady);
                changeState(PlayerState.IDLE);
                currentEvent = PlayerEvent.ENDED;
                break;
            default:
                break;

        }

    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
        Log.e(TAG, "onTimelineChanged");
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {

        Log.e(TAG, "onPlayerError error type => " + error.type);
        sendEvent(PlayerEvent.ERROR);
    }

    @Override
    public void onPositionDiscontinuity() {
        Log.e(TAG, "onPositionDiscontinuity");
    }

    @Override
    public void onTrackSelectionsChanged(TrackSelections<? extends MappingTrackSelector.MappedTrackInfo> trackSelections) {
        Log.e(TAG, "onTrackSelectionsChanged");
    }

    @Override
    public void load(Uri mediaSourceUri, boolean shouldAutoPlay) {
        Log.e(TAG, "load");

        if (player == null) {
            initializePlayer(shouldAutoPlay);
        }

        if (playerNeedsSource) {
            preparePlayer(mediaSourceUri);
        }
    }



    @Override
    public View getView() {
        return exoPlayerView;
    }

    @Override
    public void play() {
        if(player.getPlayWhenReady()) {
            return;
        }

        if(currentState.equals(PlayerState.READY)){
            if(!firstPlay){
                sendEvent(PlayerEvent.PLAY);
            }else{
                firstPlay = false;
            }
            sendEvent(PlayerEvent.PLAYING);
        }

        player.setPlayWhenReady(true);

    }

    @Override
    public void pause() {
        if(!player.getPlayWhenReady()){
            return;
        }
        sendEvent(PlayerEvent.PAUSE);
        player.setPlayWhenReady(false);
    }

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public void seekTo(long position) {
        isSeeking = true;
        sendEvent(PlayerEvent.SEEKING);
        player.seekTo(position);
    }

    @Override
    public boolean shouldAutoPlay() {
        return player.getPlayWhenReady();
    }

    @Override
    public void setAutoPlay(boolean shouldAutoplay) {
        player.setPlayWhenReady(shouldAutoplay);
    }

    public void setEventTrigger(final PlayerController.EventTrigger eventTrigger) {
        this.eventTrigger = eventTrigger;
    }

    public void setStateChangedTrigger(PlayerController.StateChangedTrigger stateChangedTrigger) {
        this.stateChangedTrigger = stateChangedTrigger;
    }

}
