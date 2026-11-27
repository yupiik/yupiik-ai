#!/bin/sh
# This packaged file is never opened for writing by test fixtures.
# The generated body is shell input, not an execve target subject to ETXTBSY.
exec /bin/sh "$0.body" "$@"
