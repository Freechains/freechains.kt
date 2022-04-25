#!/usr/bin/env sh
exec java -Xmx500M -Xms500M -ea -cp "$(dirname "$0")"/Freechains.jar org.freechains.cli.MainKt "$@"
