LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_CPP_EXTENSION := cxx

LOCAL_SRC_FILES :=  \
    vncpasswd.cxx

LOCAL_CFLAGS := -Ofast -Wall -Wformat=2 -DNDEBUG -UNDEBUG
LOCAL_CFLAGS += -Wno-unused-parameter

LOCAL_CPPFLAGS := -std=c++11 -fexceptions -frtti

LOCAL_SHARED_LIBRARIES := \
    libbinder \
    libcrypto \
    libcutils \
    libgui \
    libjpeg \
    libssl \
    libui \
    libutils \
    libz

LOCAL_STATIC_LIBRARIES += \
    libtigervnc

LOCAL_C_INCLUDES += \
    external/tigervnc/common

LOCAL_MODULE := vncpasswd
LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
