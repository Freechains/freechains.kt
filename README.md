# Freechains

## Prerequisites

Install [SDKMAN](https://sdkman.io/) and the required tools:

```
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh
sdk install java 11.0.22-tem
sdk install gradle 7.6.1
sdk install kotlin 1.8.0
```

## Build

```
git clone https://github.com/Freechains/freechains.kt/
cd freechains.kt
./gradlew build
```

The JAR is generated at `build/libs/freechains-1.0-SNAPSHOT.jar`.

## Test

```
./gradlew test
```

## Install

```
cd src/test/shell/
sudo make install     # copy to /usr/local/bin
```

## Use

```
freechains-host --version
freechains --version
```
