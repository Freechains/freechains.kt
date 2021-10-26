#!/usr/bin/env sh
exec nice -n 19 java -Xmx50M -Xms50M -ea -cp "$(dirname "$0")"/Freechains.jar org.freechains.sync.MainKt "$@"