/*
 * Copyright (C) 2026 The a6l-eink-oss authors
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

package com.hmct.epd;

/**
 * Callback through which the E-ink service reports a detected direction
 * (display type) to a client. Implemented on the client side by
 * {@link EpdManager.DirectionDetect}.
 *
 * Declaration order fixes the transaction codes (FIRST_CALL_TRANSACTION + n)
 * and must not be changed; see spec 01, section 3.2.
 *
 * {@hide}
 */
interface IDirectionDetect {
    /* 1 */ void onDirectionChanged(int mode, long requestTime);
    /* 2 */ int getListenerType();
    /* 3 */ long getRequestTime();
}
