/*
 * Copyright (C) 2026 The hk-hisense-a6l-eink authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.a6leink.epd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest receiver for LOCKED_BOOT_COMPLETED / BOOT_COMPLETED. The service
 * also registers a runtime receiver; whichever arrives first starts the
 * "E-ink ready" delay, later ones are ignored.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        EpdService service = EpdApplication.service();
        if (service != null) service.scheduleEinkReady("manifest " + intent.getAction());
    }
}
