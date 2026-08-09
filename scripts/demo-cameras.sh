#!/usr/bin/env bash
# Synthetic RTSP cameras for trying this module without hardware.
#
#   scripts/demo-cameras.sh up        start the demo cameras
#   scripts/demo-cameras.sh down      stop them and leave nothing behind
#   scripts/demo-cameras.sh status    are they running, and on what URLs
#
# Point a Camera device's "RTSP URL Override" at the URLs `up` prints.
#
# WHY THIS EXISTS RATHER THAN THE THREE COMMANDS IT REPLACES
#
# The README used to give a bare `docker run -d` plus two foreground ffmpeg
# commands, and said nothing about stopping any of them. Nobody who follows
# instructions like that ever gets their machine back: one of these was found
# with ffmpeg encoding 25 fps for a straight 24 hours, into a 17 MB logfile, on
# a tmpfs, for a demo that had finished the previous day. Three processes to
# start and no documented way to stop them is a leak with a manual.
#
# So: ONE container owns everything. MediaMTX's -ffmpeg image has ffmpeg built
# in, and `runOnDemand` starts an encoder only while something is actually
# pulling the stream and stops it when the last reader leaves -- so an idle demo
# costs nothing, which is the failure above made structurally impossible.
# `--rm` means even `docker stop` (or a crash) leaves no container behind, and
# every setting arrives as an MTX_* environment variable, so there is no config
# file to bind-mount, drift from, or leave in a scratchpad someone deletes.

set -euo pipefail

CONTAINER="${DEMO_CAMERAS_CONTAINER:-camera-driver-demo-cameras}"
# 8554 is deliberately avoided: the gateway's own bundled go2rtc already owns it
# when the gateway is host-networked, and the collision surfaces as a stream
# that simply never opens rather than as a port error.
PORT="${DEMO_CAMERAS_PORT:-8556}"
IMAGE="${DEMO_CAMERAS_IMAGE:-bluenviron/mediamtx:latest-ffmpeg}"

# Two visibly DIFFERENT patterns, so a CameraGrid with two tiles is obviously
# showing two cameras rather than one drawn twice. Neither uses ffmpeg's
# drawtext: it needs a font file that the image does not ship, and the failure
# is a stream that never publishes rather than an error anyone would connect to
# a missing font.
# The lavfi source takes `=` before its FIRST option and `:` between the rest:
# `testsrc2=size=...:rate=25`. `testsrc2:size=...` parses as a filterchain name
# and fails inside the container with "No option name near", which surfaces to a
# reader as a bare RTSP 400 with nothing to suggest ffmpeg was ever involved.
feed() {  # feed <lavfi-source>
  printf 'ffmpeg -re -f lavfi -i %s=size=1280x720:rate=25 -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -f rtsp rtsp://localhost:$RTSP_PORT/$MTX_PATH' "$1"
}

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

case "${1:-status}" in

  up)
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --rm --name "$CONTAINER" --network host \
      -e MTX_RTSPADDRESS=":$PORT" \
      -e MTX_RTMP=no -e MTX_HLS=no -e MTX_WEBRTC=no -e MTX_SRT=no \
      -e MTX_API=no -e MTX_METRICS=no -e MTX_PPROF=no -e MTX_PLAYBACK=no \
      -e MTX_PATHS_CAM1_RUNONDEMAND="$(feed testsrc2)" \
      -e MTX_PATHS_CAM1_RUNONDEMANDRESTART=yes \
      -e MTX_PATHS_CAM2_RUNONDEMAND="$(feed smptebars)" \
      -e MTX_PATHS_CAM2_RUNONDEMANDRESTART=yes \
      "$IMAGE" >/dev/null
    echo "demo cameras up:"
    echo "  rtsp://127.0.0.1:$PORT/cam1   (test pattern)"
    echo "  rtsp://127.0.0.1:$PORT/cam2   (colour bars)"
    echo
    echo "Encoding starts when something reads the stream, not now."
    echo "Finished?  scripts/demo-cameras.sh down"
    ;;

  down)
    if docker rm -f "$CONTAINER" >/dev/null 2>&1; then
      echo "demo cameras down: $CONTAINER removed"
    else
      echo "demo cameras down: nothing running"
    fi
    ;;

  status)
    if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
      echo "running: $CONTAINER  ->  rtsp://127.0.0.1:$PORT/cam1, /cam2"
      # An encoder here means something is genuinely reading a stream. None is
      # the healthy idle state, not a fault.
      # `|| true` INSIDE the container: grep -c exits 1 on zero matches, so an
      # outer `|| echo 0` fires alongside the "0" grep already printed and the
      # count comes back as two lines.
      n=$(docker exec "$CONTAINER" sh -c 'ps -o args | grep -c "[f]fmpeg" || true' 2>/dev/null)
      echo "active encoders: ${n:-0} (0 = nothing is watching, which is fine)"
    else
      echo "not running — start with: scripts/demo-cameras.sh up"
    fi
    ;;

  -h|--help|help) usage 0 ;;
  *) echo "unknown command: $1" >&2; usage 1 ;;
esac
