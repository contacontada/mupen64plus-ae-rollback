LOCAL_PATH := $(call my-dir)
ROLLBACK_LOCAL_PATH := $(LOCAL_PATH)

###########################
# GekkoNet static library
###########################
include $(CLEAR_VARS)
LOCAL_MODULE := gekkonet

GEKKOLIB := $(ROLLBACK_LOCAL_PATH)/GekkoLib/GekkoLib

LOCAL_C_INCLUDES := $(GEKKOLIB)/include $(GEKKOLIB)/thirdparty
LOCAL_CFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2
LOCAL_CPPFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2 -std=c++17 -frtti

LOCAL_SRC_FILES := \
    $(GEKKOLIB)/src/gekkonet.cpp \
    $(GEKKOLIB)/src/game_session.cpp \
    $(GEKKOLIB)/src/spectator_session.cpp \
    $(GEKKOLIB)/src/stress_session.cpp \
    $(GEKKOLIB)/src/backend.cpp \
    $(GEKKOLIB)/src/event.cpp \
    $(GEKKOLIB)/src/net.cpp \
    $(GEKKOLIB)/src/input.cpp \
    $(GEKKOLIB)/src/player.cpp \
    $(GEKKOLIB)/src/storage.cpp \
    $(GEKKOLIB)/src/sync.cpp

include $(BUILD_STATIC_LIBRARY)

###########################
# mupen64plus-core
#
# Pulled into this module's own ndk-build graph so the linker can resolve
# CoreDoCommand and friends directly (relying on Android's dynamic linker to
# resolve them against a *separately built* libmupen64plus-core.so via
# LOCAL_SHARED_LIBRARIES + APP_ALLOW_MISSING_DEPS was tried first and does
# not reliably link - see erros-e-solucoes.pdf, Erro 2/3).
#
# mupen64plus-core/Android.mk reassigns the global JNI_LOCAL_PATH/LOCAL_PATH
# variables as a side effect of building its own module, so
# ROLLBACK_LOCAL_PATH (saved above, before this include) is what the rest
# of this file must use afterwards - not LOCAL_PATH.
###########################
include $(ROLLBACK_LOCAL_PATH)/../../mupen64plus-core/Android.mk

###########################
# Rollback JNI bridge
###########################
include $(CLEAR_VARS)
LOCAL_PATH := $(ROLLBACK_LOCAL_PATH)
LOCAL_MODULE := mupen64plus-rollback

LOCAL_C_INCLUDES := \
    $(GEKKOLIB)/include \
    $(GEKKOLIB)/thirdparty \
    $(ROLLBACK_LOCAL_PATH)/../../mupen64plus-core/upstream/src

LOCAL_CPPFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2 -std=c++17 -frtti
LOCAL_CFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2

LOCAL_SRC_FILES := $(ROLLBACK_LOCAL_PATH)/rollback_jni.cpp

LOCAL_STATIC_LIBRARIES := gekkonet
LOCAL_SHARED_LIBRARIES := mupen64plus-core

LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
