LOCAL_PATH := $(call my-dir)

CORE_DIR := $(LOCAL_PATH)/../src

HAVE_NTSC := 1

include $(LOCAL_PATH)/../Makefile.common

COREFLAGS := $(COREDEFINES) -DPSS_STYLE=1 $(INCFLAGS)

# Exact core revision; never inherit the enclosing application's Git revision.
COREFLAGS += -DGIT_VERSION=\"236ccdfc-rommio3\" -g0 -ffile-prefix-map=$(LOCAL_PATH)/..=fceumm

include $(CLEAR_VARS)
LOCAL_MODULE    := fceumm_libretro_android
LOCAL_SRC_FILES := $(SOURCES_C)
LOCAL_CFLAGS    := $(COREFLAGS)
LOCAL_LDFLAGS   := -Wl,-version-script=$(CORE_DIR)/drivers/libretro/link.T,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
