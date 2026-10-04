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

import android.app.Application;
import android.os.UserHandle;
import android.util.Log;

/**
 * Persistent, direct-boot-aware application hosting the {@code epd} service
 * (spec 02 section 1.1). The service is registered in onCreate before any
 * other work.
 */
public class EpdApplication extends Application {
    private static volatile EpdService sService;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            // Settings pages may run in a secondary user's process; the
            // service exists once, in the system user.
            return;
        }
        synchronized (EpdApplication.class) {
            if (sService == null) {
                EpdService service = new EpdService(this);
                service.start();
                sService = service;
            }
        }
        Log.i(EpdService.TAG, "E-ink service started");
    }

    /** The running service, or null in a process that does not host it. */
    static EpdService service() {
        return sService;
    }
}
