# Vendored nanopb runtime (zlib license, see LICENSE.txt), version in VERSION.
# Must match the nanopb_generator version used by tools/gen_proto.sh.
idf_component_register(SRCS "pb_common.c" "pb_encode.c" "pb_decode.c"
                       INCLUDE_DIRS ".")
