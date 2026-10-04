# Copyright (C) 2026 jetbee
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Product fragment for the clean-room E-ink stack (specs 01, 02, 04 and 05).
# Include it from the device's product makefile:
#   $(call inherit-product, <path-to-hk-hisense-a6l-eink>/epd/epd.mk)
# and include BoardConfigEpd.mk from BoardConfig.mk.

A6L_EPD_DIR := $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))

# Client library on the boot class path (spec 01 section 1), the service
# app (spec 02 section 1.1; installs to /system_ext/priv-app/A6lEinkService)
# and the settings app (spec 04 section 6; /system_ext/priv-app/A6lEinkSettings).
PRODUCT_PACKAGES += \
    com.hmct.epd \
    A6lEinkService \
    A6lEinkSettings

PRODUCT_BOOT_JARS += com.hmct.epd

# Init property bridge (spec 02 section 1.4).
PRODUCT_COPY_FILES += \
    $(A6L_EPD_DIR)/rootdir/etc/init.a6l.epd.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/init.a6l.epd.rc

# Privileged-permission allowlist of the service app (spec 05 section 6.3:
# MODIFY_AUDIO_ROUTING for the video lock). Must be installed with the app.
PRODUCT_COPY_FILES += \
    $(A6L_EPD_DIR)/permissions/privapp-permissions-a6leink.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-a6leink.xml

# lineage-sdk overlay that loads the E-ink key handler (spec 02 section 5.1).
PRODUCT_PACKAGE_OVERLAYS += $(A6L_EPD_DIR)/overlay

# Gate of getSystemService("epd") (spec 01 section 1). The frameworks/base
# patch registers the service whatever the value; stock apps may check it.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.hmct.epd.support=1
