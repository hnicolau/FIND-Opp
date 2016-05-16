/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ul.fcul.lasige.findvictim.gcm;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.gcm.GcmPubSub;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import java.io.IOException;

import ul.fcul.lasige.findvictim.R;
import ul.fcul.lasige.findvictim.data.TokenStore;
import ul.fcul.lasige.findvictim.webservices.RequestServer;

public class RegistrationIntentService extends IntentService {

    private static final String TAG = RegistrationIntentService.class.getSimpleName();
    private static final String[] TOPICS = {"global"};

    private static final String EXTRA_LOCALE = "ul.fcul.lasige.findvictim.extra.LOCALE";
    private static final String EXTRA_MAC = "ul.fcul.lasige.findvictim.extra.MAC";
    private static final String EXTRA_EMAIL = "ul.fcul.lasige.findvictim.extra.EMAIL";

    public RegistrationIntentService() {
        super(TAG);
    }

    /**
     * Starts this service with the given parameters. If the service is
     * already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startGCMRegistration(Context context, String locale, String macAddress, String googleAccount) {
        Intent intent = new Intent(context, RegistrationIntentService.class);
        intent.putExtra(EXTRA_LOCALE, locale);
        intent.putExtra(EXTRA_MAC, macAddress);
        intent.putExtra(EXTRA_EMAIL, googleAccount);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        try {
            // [START register_for_gcm]
            // Initially this call goes out to the network to retrieve the token, subsequent calls
            // are local.
            // R.string.gcm_defaultSenderId (the OriginMac ID) is typically derived from google-services.json.
            // See https://developers.google.com/cloud-messaging/android/start for details on this file.
            // [START get_token]
            InstanceID instanceID = InstanceID.getInstance(this);
            String token = instanceID.getToken(getString(R.string.gcm_defaultSenderId),
                    GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
            // [END get_token]

            // send any registration to your app's servers.
            final String locale = intent.getStringExtra(EXTRA_LOCALE);
            final String mac = intent.getStringExtra(EXTRA_MAC);
            final String email = intent.getStringExtra(EXTRA_EMAIL);
            sendRegistrationToServer(locale, mac, email, token);


            // [END register_for_gcm]
        } catch (Exception e) {
            Log.d(TAG, "Failed to complete token refresh", e);
            // If an exception happens while fetching the new token or updating our registration data
            // on a third-party server, this ensures that we'll attempt the update at a later time.
            TokenStore.deleteRegistration(this);

            // Notify UI that registration has has failed, so the user can be notified.
            Intent registrationComplete = new Intent(TokenStore.KEY_REGISTRATION_COMPLETE);
            LocalBroadcastManager.getInstance(this).sendBroadcast(registrationComplete);
        }
    }

    /**
     * Persist registration to third-party servers.
     *
     * Modify this method to associate the user's GCM registration token with any server-side account
     * maintained by your application.
     *
     * @param token The new token.
     */
    private void sendRegistrationToServer(String locale, String mac, String email, String token) {
        RequestServer.register(getApplicationContext(), locale, mac, email, token);
    }

    /**
     * Subscribe to any GCM topics of interest, as defined by the TOPICS constant.
     *
     * @param token GCM token
     * @throws IOException if unable to reach the GCM PubSub service
     */
    // [START subscribe_topics]
    private void subscribeTopics(String token) throws IOException {
        GcmPubSub pubSub = GcmPubSub.getInstance(this);
        for (String topic : TOPICS) {
            pubSub.subscribe(token, "/topics/" + topic, null);
        }
    }
    // [END subscribe_topics]

}
