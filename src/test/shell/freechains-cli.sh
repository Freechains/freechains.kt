#!/usr/bin/env sh
exec java -Xmx5M -Xms5M -ea -cp "$(dirname "$0")"/Freechains.jar org.freechains.cli.MainKt "$@"