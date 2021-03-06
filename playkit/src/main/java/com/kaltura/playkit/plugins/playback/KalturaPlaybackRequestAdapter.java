/*
 * ============================================================================
 * Copyright (C) 2017 Kaltura Inc.
 * 
 * Licensed under the AGPLv3 license, unless a different license for a
 * particular library is specified in the applicable library path.
 * 
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/agpl-3.0.html
 * ============================================================================
 */

package com.kaltura.playkit.plugins.playback;

import android.content.Context;
import android.net.Uri;

import com.kaltura.playkit.PKRequestParams;
import com.kaltura.playkit.Player;

import static com.kaltura.playkit.PlayKitManager.CLIENT_TAG;
import static com.kaltura.playkit.Utils.toBase64;

/**
 * Created by Noam Tamim @ Kaltura on 28/03/2017.
 */
public class KalturaPlaybackRequestAdapter implements PKRequestParams.Adapter {

    private final String packageName;
    private String playSessionId;
    
    public static void setup(Context context, Player player) {
        KalturaPlaybackRequestAdapter decorator = new KalturaPlaybackRequestAdapter(context.getPackageName(), player.getSessionId());
        player.getSettings().setContentRequestAdapter(decorator);
    }

    private KalturaPlaybackRequestAdapter(String packageName, String playSessionId) {
        this.packageName = packageName;
        this.playSessionId = playSessionId;
    }
    
    @Override
    public PKRequestParams adapt(PKRequestParams requestParams) {
        Uri url = requestParams.url;

        if (url.getPath().contains("/playManifest/")) {
            Uri alt = url.buildUpon()
                    .appendQueryParameter("clientTag", CLIENT_TAG)
                    .appendQueryParameter("referrer", toBase64(packageName.getBytes()))
                    .appendQueryParameter("playSessionId", playSessionId)
                    .build();
            return new PKRequestParams(alt, requestParams.headers);
        }

        return requestParams;
    }

    @Override
    public void updateParams(Player player) {
        this.playSessionId = player.getSessionId();
    }
}
