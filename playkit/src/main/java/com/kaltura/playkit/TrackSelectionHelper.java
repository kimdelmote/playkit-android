package com.kaltura.playkit;

import android.nfc.Tag;
import android.util.Log;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.kaltura.playkit.player.ExoPlayerWrapper;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.SelectionOverride;


import java.util.ArrayList;
import java.util.List;

import static com.google.android.exoplayer2.C.TRACK_TYPE_UNKNOWN;

/**
 * Responsible for generating/sorting/holding and changing track info.
 * Created by anton.afanasiev on 22/11/2016.
 */

public class TrackSelectionHelper {

    private static final PKLog log = PKLog.get("TrackSelectionHelper");

    private static final TrackSelection.Factory FIXED_FACTORY = new FixedTrackSelection.Factory();

    public static final int TRACK_VIDEO = 0;
    public static final int TRACK_AUDIO = 1;
    public static final int TRACK_SUBTITLE = 2;
    public static final int TRACK_AUTO = Integer.MIN_VALUE;

    private static final int RENDERER_INDEX = 0;
    private static final int GROUP_INDEX = 1;
    private static final int TRACK_INDEX = 2;
    private static final int TRACK_RENDERERS_AMOUNT = 3;

    private ExoPlayerWrapper.TrackInfoReadyListener trackReadyListener;

    private TrackGroupArray trackGroups;
    private final MappingTrackSelector selector;
    private MappingTrackSelector.MappedTrackInfo mappedTrackInfo;
    private final TrackSelection.Factory adaptiveTrackSelectionFactory;

    private List<BaseTrackInfo> videoTracksInfo = new ArrayList<>();
    private List<BaseTrackInfo> audioTracksInfo = new ArrayList<>();
    private List<BaseTrackInfo> subtitleTracksInfo = new ArrayList<>();

    private boolean isDisabled;


    /**
     * @param selector                      The track selector.
     * @param adaptiveTrackSelectionFactory A factory for adaptive video {@link TrackSelection}s,
     *                                      or null if the selection helper should not support adaptive video.
     */
    public TrackSelectionHelper(MappingTrackSelector selector,
                                TrackSelection.Factory adaptiveTrackSelectionFactory) {
        this.selector = selector;
        this.adaptiveTrackSelectionFactory = adaptiveTrackSelectionFactory;
    }

    /**
     * Change the currently playing track.
     *
     * @param mappedTrackInfo
     */
    public void changeTrack(String uniqueId, MappedTrackInfo mappedTrackInfo) {

        int[] uniqueTrackId = convertUniqueId(uniqueId);
        int rendererIndex = uniqueTrackId[RENDERER_INDEX];
        isDisabled = selector.getRendererDisabled(rendererIndex);
        trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);

        SelectionOverride override = retrieveOverrideSelection(uniqueTrackId);
        overrideTrack(rendererIndex, override);
    }

    private int[] convertUniqueId(String uniqueId) {
        int[] convertedUniqueId = new int[3];
        String[] strArray = uniqueId.split(",");

        for (int i = 0; i < strArray.length; i++) {
            convertedUniqueId[i] = Integer.parseInt(strArray[i]);
        }
        return convertedUniqueId;
    }

    private SelectionOverride retrieveOverrideSelection(int[] uniqueId) {

        SelectionOverride override;

        int rendererIndex = uniqueId[RENDERER_INDEX];
        int groupIndex = uniqueId[GROUP_INDEX];
        int trackIndex = uniqueId[TRACK_INDEX];


        boolean isAdaptive = trackIndex == TRACK_AUTO ? true : false;


        if (isAdaptive) {

            List<Integer> adaptiveTrackIndexesList = new ArrayList<>();
            int[] adaptiveTrackIndexes;

            switch (rendererIndex) {
                case TRACK_VIDEO:

                    VideoTrackInfo videoTrackInfo;

                    for (int i = 1; i < videoTracksInfo.size(); i++) {
                        videoTrackInfo = (VideoTrackInfo) videoTracksInfo.get(i);
                        if (getIndexFromUniueId(videoTrackInfo.getUniqueId(), GROUP_INDEX) == groupIndex) {
                            adaptiveTrackIndexesList.add(getIndexFromUniueId(videoTrackInfo.getUniqueId(), TRACK_INDEX));
                        }
                    }
                    break;
                case TRACK_AUDIO:
                    AudioTrackInfo audioTrackInfo;
                    for (int i = 1; i < audioTracksInfo.size(); i++) {
                        audioTrackInfo = (AudioTrackInfo) audioTracksInfo.get(i);
                        if (getIndexFromUniueId(audioTrackInfo.getUniqueId(), GROUP_INDEX) == groupIndex) {
                            adaptiveTrackIndexesList.add(getIndexFromUniueId(audioTrackInfo.getUniqueId(), TRACK_INDEX));
                        }
                    }
                    break;
            }

            adaptiveTrackIndexes = convertAdaptiveListToArray(adaptiveTrackIndexesList);
            override = new MappingTrackSelector.SelectionOverride(adaptiveTrackSelectionFactory, groupIndex, adaptiveTrackIndexes);
        } else {
            override = new MappingTrackSelector.SelectionOverride(FIXED_FACTORY, groupIndex, trackIndex);
        }

        return override;
    }

    private int getIndexFromUniueId(String uniqueId, int groupIndex) {
        String[] strArray = uniqueId.split(",");
        return Integer.valueOf(strArray[groupIndex]);
    }

    private int[] convertAdaptiveListToArray(List<Integer> adaptiveTrackIndexesList) {
        int[] adaptiveTrackIndexes = new int[adaptiveTrackIndexesList.size()];
        for (int i = 0; i < adaptiveTrackIndexes.length; i++) {
            adaptiveTrackIndexes[i] = adaptiveTrackIndexesList.get(i);
        }

        return adaptiveTrackIndexes;
    }

    private void overrideTrack(int trackType, SelectionOverride override) {
        //if renderer is disabled we will hide it.
        selector.setRendererDisabled(trackType, isDisabled);
        if (override != null) {
            //actually change track.
            selector.setSelectionOverride(trackType, trackGroups, override);
        } else {
            //clear all the selections if the override is null.
            selector.clearSelectionOverrides(trackType);
        }
    }

    /**
     * Sort track info. We need it in order to have the correct representation of the tracks.
     *
     * @param mappedTrackInfo
     */
    public void sortTracksInfo(MappedTrackInfo mappedTrackInfo) {

        this.mappedTrackInfo = mappedTrackInfo;
        TracksInfo tracksInfo = initTracksInfo();
        //notify the ExoplayerWrapper that track info is ready.
        if (trackReadyListener != null) {
            trackReadyListener.onTrackInfoReady(tracksInfo);
        }
    }

    /**
     * Mapping the track info.
     */
    private TracksInfo initTracksInfo() {
        TrackGroupArray trackGroupArray;
        TrackGroup trackGroup;
        Format format;
        //run through the all renders.
        for (int rendererIndex = 0; rendererIndex < TRACK_RENDERERS_AMOUNT; rendererIndex++) {

            //the trackGroupArray of the current renderer.
            trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex);

            //run through the all track groups in current renderer.
            for (int groupIndex = 0; groupIndex < trackGroupArray.length; groupIndex++) {

                // the track group of the current trackGroupArray.
                trackGroup = trackGroupArray.get(groupIndex);

                //run through the all tracks in current trackGroup.
                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {

                    // the format of the current trackGroup.
                    format = trackGroup.getFormat(trackIndex);
                    maybeAddAutoTrack(trackGroupArray, rendererIndex, groupIndex, format);

                    //filter all the unsupported and unknown formats.
                    if (isFormatSupported(rendererIndex, groupIndex, trackIndex) && format.id != null) {
                        String uniqueId = getUniqueId(rendererIndex, groupIndex, trackIndex);
                        switch (rendererIndex) {
                            case TRACK_VIDEO:
                                videoTracksInfo.add(new VideoTrackInfo(uniqueId, format.bitrate, format.width, format.height, false));
                                break;
                            case TRACK_AUDIO:
                                audioTracksInfo.add(new AudioTrackInfo(uniqueId, format.language, format.bitrate, false));
                                break;

                            case TRACK_SUBTITLE:
                                subtitleTracksInfo.add(new SubtitleTrackInfo(uniqueId, format.language));
                                break;
                        }
                    }
                }
            }
        }

        return new TracksInfo(videoTracksInfo, audioTracksInfo, subtitleTracksInfo);
    }

    private void maybeAddAutoTrack(TrackGroupArray trackGroupArray, int rendererIndex, int groupIndex, Format format) {
        String uniqueId = getUniqueId(rendererIndex, groupIndex, TRACK_AUTO);
        if (isAdaptive(trackGroupArray, rendererIndex, groupIndex) && !adaptiveTrackInfoAlreadyExist(uniqueId, rendererIndex)) {
            switch (rendererIndex) {
                case TRACK_VIDEO:
                    videoTracksInfo.add(new VideoTrackInfo(uniqueId, 0, 0, 0, true));
                    break;
                case TRACK_AUDIO:
                    audioTracksInfo.add(new AudioTrackInfo(uniqueId, format.language, 0, true));
                    break;
                case TRACK_SUBTITLE:
                    subtitleTracksInfo.add(new SubtitleTrackInfo(uniqueId, format.language));
                    break;
            }
        }
    }

    private boolean adaptiveTrackInfoAlreadyExist(String uniqueId, int rendererIndex) {
        switch (rendererIndex){
            case TRACK_VIDEO:
                for(BaseTrackInfo trackInfo : videoTracksInfo){
                    if(trackInfo.getUniqueId().equals(uniqueId)){
                        return true;
                    }
                }
                break;
            case TRACK_AUDIO:
                for(BaseTrackInfo trackInfo : audioTracksInfo){
                    if(trackInfo.getUniqueId().equals(uniqueId)){
                        return true;
                    }
                }
                break;
            case TRACK_SUBTITLE:
                for(BaseTrackInfo trackInfo : subtitleTracksInfo){
                    if(trackInfo.getUniqueId().equals(uniqueId)){
                        return true;
                    }
                }
                break;
        }
        return false;
    }

    private String getUniqueId(int rendererIndex, int groupIndex, int trackIndex) {
        StringBuilder uniqueStringBuilder = new StringBuilder();
        uniqueStringBuilder.append(rendererIndex);
        uniqueStringBuilder.append(",");
        uniqueStringBuilder.append(groupIndex);
        uniqueStringBuilder.append(",");
        uniqueStringBuilder.append(trackIndex);
        return uniqueStringBuilder.toString();
    }

    private boolean isFormatSupported(int rendererCount, int groupIndex, int trackIndex) {
        return mappedTrackInfo.getTrackFormatSupport(rendererCount, groupIndex, trackIndex)
                == RendererCapabilities.FORMAT_HANDLED;
    }

    public boolean isAdaptive(TrackGroupArray trackGroupArray, int rendererIndex, int groupIndex) {
        return adaptiveTrackSelectionFactory != null
                && mappedTrackInfo.getAdaptiveSupport(rendererIndex, groupIndex, false)
                != RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
                && trackGroupArray.get(groupIndex).length > 1;
    }

    public void setTrackReadyListener(ExoPlayerWrapper.TrackInfoReadyListener trackReadyListener) {
        this.trackReadyListener = trackReadyListener;
    }


    public void release() {
        trackReadyListener = null;
        videoTracksInfo.clear();
        audioTracksInfo.clear();
        subtitleTracksInfo.clear();
    }

}
