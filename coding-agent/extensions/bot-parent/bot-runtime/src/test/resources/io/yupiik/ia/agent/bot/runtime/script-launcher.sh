#!/bin/sh
# Never opened for writing by fixtures; the generated body is input, not an execve target.
# Source it to preserve the invoked path as $0 for all fixture sidecars.
. "$0.body"
