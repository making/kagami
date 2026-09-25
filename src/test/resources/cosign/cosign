#!/bin/sh
# Mock cosign binary used by the verification tests. It records its arguments and its
# behaviour is scripted through control files so each test can decide the outcome:
#   /tmp/kagami-cosign-mock/exit-code  the exit code to end with
#   /tmp/kagami-cosign-mock/sleep      sleep before exiting (used to test the timeout)
DIR=/tmp/kagami-cosign-mock
mkdir -p "$DIR"
printf '%s\n' "$@" > "$DIR/args"
echo "mock cosign stdout"
echo "mock cosign stderr" >&2
if [ -f "$DIR/sleep" ]; then
  sleep 30
fi
if [ -f "$DIR/exit-code" ]; then
  exit "$(cat "$DIR/exit-code")"
fi
exit 0
