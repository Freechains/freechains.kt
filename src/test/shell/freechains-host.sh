#!/usr/bin/env sh
exec nice -n 19 java -Xmx50M -Xms50M -ea -cp "$(dirname "$0")"/slf4j-nop-2.0.0-alpha1.jar:"$(dirname "$0")"/Freechains.jar org.freechains.host.MainKt "$@"
