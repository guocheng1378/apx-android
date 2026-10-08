# CMake generated Testfile for 
# Source directory: /workspace/pc/host
# Build directory: /workspace/build_test
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
add_test([=[arbiter_test]=] "/workspace/build_test/arbiter_test")
set_tests_properties([=[arbiter_test]=] PROPERTIES  _BACKTRACE_TRIPLES "/workspace/pc/host/CMakeLists.txt;353;add_test;/workspace/pc/host/CMakeLists.txt;0;")
add_test([=[utf16_convert_test]=] "/workspace/build_test/utf16_convert_test")
set_tests_properties([=[utf16_convert_test]=] PROPERTIES  _BACKTRACE_TRIPLES "/workspace/pc/host/CMakeLists.txt;359;add_test;/workspace/pc/host/CMakeLists.txt;0;")
add_test([=[ctrl_frame_test]=] "/workspace/build_test/ctrl_frame_test")
set_tests_properties([=[ctrl_frame_test]=] PROPERTIES  _BACKTRACE_TRIPLES "/workspace/pc/host/CMakeLists.txt;366;add_test;/workspace/pc/host/CMakeLists.txt;0;")
add_test([=[wireless_session_lifecycle_test]=] "/workspace/build_test/wireless_session_lifecycle_test")
set_tests_properties([=[wireless_session_lifecycle_test]=] PROPERTIES  _BACKTRACE_TRIPLES "/workspace/pc/host/CMakeLists.txt;376;add_test;/workspace/pc/host/CMakeLists.txt;0;")
subdirs("apxdisp_in_host")
