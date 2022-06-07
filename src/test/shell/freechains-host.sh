#!/usr/bin/env sh
#exec nice -n 19 java -Xss100M -Xmx5000M -Xms5000M -ea -cp "$(dirname "$0")"/slf4j-nop-2.0.0-alpha1.jar:"$(dirname "$0")"/Freechains.jar org.freechains.host.MainKt "$@"
exec java -ea -cp "$(dirname "$0")"/slf4j-nop-2.0.0-alpha1.jar:"$(dirname "$0")"/Freechains.jar org.freechains.host.MainKt "$@"
