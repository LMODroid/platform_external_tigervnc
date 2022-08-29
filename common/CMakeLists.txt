add_subdirectory(os)
add_subdirectory(rdr)
add_subdirectory(network)
add_subdirectory(Xregion)
add_subdirectory(rfb)

# For any convenience libraries that are linked into libvnc.so, we need to
# explicitly build their corresponding sources using PIC.  WIN32 is excluded
# because PIC code does not exist on that platform and MinGW complains if -fPIC
# is passed (additionally, libvnc is not used on Windows.)

if(NOT WIN32)
  set_target_properties(os rdr network Xregion rfb
    PROPERTIES COMPILE_FLAGS -fPIC)
endif()

# Generate Android.mk
foreach(ANDROID_SRC_PATH "os/*.cxx" "rdr/*.cxx" "network/*.cxx" "Xregion/*.c")
  file(GLOB ANDROID_SOURCES RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} ${ANDROID_SRC_PATH})
  foreach(SRC ${ANDROID_SOURCES})
    set(ANDROID_SRC_FILES "${ANDROID_SRC_FILES} \\\n    ${SRC}")
  endforeach()
endforeach()

foreach(SRC ${RFB_SOURCES} Logger_android.cxx)
  set(ANDROID_RFB_SRC_FILES "${ANDROID_RFB_SRC_FILES} \\\n    rfb/${SRC}")
endforeach()

configure_file(Android.mk.in Android.mk @ONLY)
