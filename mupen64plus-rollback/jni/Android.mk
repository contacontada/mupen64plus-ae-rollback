LOCAL_PATH := $(call my-dir)

# The rollback bridge links against the core module; include its NDK module
# definition in this build as well.
include $(LOCAL_PATH)/../../mupen64plus-core/Android.mk
LOCAL_PATH := $(call my-dir)

###########################
# GekkoNet static library
###########################
include $(CLEAR_VARS)
LOCAL_MODULE := gekkonet

GEKKOLIB := $(LOCAL_PATH)/GekkoLib/GekkoLib

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
# Rollback JNI bridge
###########################
include $(CLEAR_VARS)
LOCAL_PATH := $(call my-dir)
LOCAL_MODULE := mupen64plus-rollback

LOCAL_C_INCLUDES := \
    $(GEKKOLIB)/include \
    $(GEKKOLIB)/thirdparty \
    $(LOCAL_PATH)/../../mupen64plus-core/upstream/src

LOCAL_CPPFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2 -std=c++17 -frtti
LOCAL_CFLAGS := -DGEKKONET_STATIC -DGEKKONET_NO_ASIO -O2

LOCAL_SRC_FILES := rollback_jni.cpp

LOCAL_STATIC_LIBRARIES := gekkonet
LOCAL_SHARED_LIBRARIES := mupen64plus-core

LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
