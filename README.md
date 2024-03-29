# Compile from sources

1. Clone the source repository:

```
$ git clone https://github.com/Freechains/freechains.kt/
```


2. Install the Java SDK:

```
sudo apt install default-jdk
```

3. Open `IntelliJ IDEA` (version `2023.1.2`):
    - Open project/directory `freechains.kt/`
    - Wait for all imports (takes long...)
    - Run self tests:
        - On the left pane, click tab `Project`:
            - Double click file `freechains.kt -> src -> test -> kotlin -> Test.kt`
        - Press `CTRL-SHIFT-F10` to run all tests (takes long...)
    - Generate artifacts:
        - Click `File -> Project Structure -> Artifacts -> + -> JAR -> From modules with dependencies`
        - Click `Module -> freechains.kt.main`
        - Click `OK`
        - Verify that `freechains.kt.main:jar` appears at the top
        - Click `OK`
    - Rebuild artifacts:
        - Click `Build -> Build artifacts -> Build`

4. Install & Test binaries:

```
$ cd freechains.kt/src/test/shell/
$ sudo make install     # copy to /usr/local/bin
$ cd test/
$ ./tests.sh
```

5. Use Freechains:

```
$ freechains-host --version
$ freechains --version
```
