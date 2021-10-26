#!/usr/bin/env sh
exec java -Xmx50M -Xms50M -ea -cp "$(dirname "$0")"/Freechains.jar org.freechains.cli.MainKt "$@"